#!/usr/bin/env python3
"""Build a four-class dashcam-style detection set for the tracker vision contract.

Class order is the library contract, not a dataset path:
    0 car, 1 truck, 2 bus, 3 trolleybus

COCO boxes for car/truck/bus are the official human annotations. Trolleybus
boxes are silver labels: a COCO-pretrained nano detector proposes vehicle
boxes on Wikimedia Commons photos filed as trolleybuses, and a bus box is
stored as class 3. COCO buses are never relabelled as trolleybuses.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import random
import re
import time
import urllib.request
import zipfile
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
CACHE = DATA / "cache"
COCO_ZIP = "http://images.cocodataset.org/annotations/annotations_trainval2017.zip"
COCO_CATEGORY = {3: 0, 8: 1, 6: 2}  # COCO category_id -> our index
QUOTA = {0: 700, 1: 350, 2: 350}  # selected images that contain the class
COMMONS_TARGET = 400
MIN_PHOTO_YEAR = 1995
YEAR_RE = re.compile(r"\b(?:18|19|20)\d{2}\b")
USER_AGENT = "ExKMPTrackerVisionDataset/1.0 (vehicle detection; educational)"

NAMES = {0: "car", 1: "truck", 2: "bus", 3: "trolleybus"}


def fetch(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and dest.stat().st_size > 0:
        return
    temporary = dest.with_suffix(dest.suffix + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as output:
        while True:
            chunk = response.read(1 << 20)
            if not chunk:
                break
            output.write(chunk)
    temporary.replace(dest)


def load_coco() -> tuple[dict, dict]:
    zip_path = CACHE / "annotations_trainval2017.zip"
    fetch(COCO_ZIP, zip_path)
    images: dict[int, dict] = {}
    boxes: dict[int, list[tuple[int, float, float, float, float]]] = defaultdict(list)
    with zipfile.ZipFile(zip_path) as archive:
        for name in ("annotations/instances_train2017.json", "annotations/instances_val2017.json"):
            payload = json.load(io.TextIOWrapper(archive.open(name), encoding="utf-8"))
            split = "train2017" if "train" in name else "val2017"
            for image in payload["images"]:
                image["split"] = split
                images[image["id"]] = image
            for ann in payload["annotations"]:
                label = COCO_CATEGORY.get(ann["category_id"])
                if label is None or ann.get("iscrowd"):
                    continue
                x, y, w, h = ann["bbox"]
                if w < 8 or h < 8:
                    continue
                boxes[ann["image_id"]].append((label, x, y, w, h))
    return images, boxes


def choose_coco(images: dict, boxes: dict) -> list[int]:
    by_class: dict[int, list[int]] = defaultdict(list)
    for image_id, items in boxes.items():
        present = {label for label, *_ in items}
        for label in present:
            by_class[label].append(image_id)
    rng = random.Random(22)
    for ids in by_class.values():
        rng.shuffle(ids)
    selected: list[int] = []
    seen: set[int] = set()
    counts = {0: 0, 1: 0, 2: 0}
    for label in (2, 1, 0):
        for image_id in by_class[label]:
            if counts[label] >= QUOTA[label]:
                break
            if image_id in seen:
                continue
            selected.append(image_id)
            seen.add(image_id)
            for present in {item[0] for item in boxes[image_id]}:
                if present in counts:
                    counts[present] += 1
    return selected


def clip_box(x: float, y: float, w: float, h: float, width: int, height: int) -> tuple[float, float, float, float]:
    x2 = min(float(width), max(0.0, x) + max(0.0, w))
    y2 = min(float(height), max(0.0, y) + max(0.0, h))
    x1 = min(max(0.0, x), float(width))
    y1 = min(max(0.0, y), float(height))
    return x1, y1, max(0.0, x2 - x1), max(0.0, y2 - y1)


def yolo_line(label: int, x: float, y: float, w: float, h: float, width: int, height: int) -> str | None:
    if w < 8 or h < 8 or width < 1 or height < 1:
        return None
    cx = (x + w / 2) / width
    cy = (y + h / 2) / height
    nw = w / width
    nh = h / height
    if min(cx, cy, nw, nh) <= 0 or max(cx, cy, nw, nh) > 1:
        return None
    return f"{label} {cx:.6f} {cy:.6f} {nw:.6f} {nh:.6f}"


def split_of(key: str) -> str:
    digest = hashlib.sha1(key.encode()).hexdigest()
    return "val" if int(digest[:2], 16) < 26 else "train"  # about 15%


def write_sample(split: str, stem: str, image_bytes: bytes, lines: list[str]) -> None:
    image_path = DATA / "images" / split / f"{stem}.jpg"
    label_path = DATA / "labels" / split / f"{stem}.txt"
    image_path.parent.mkdir(parents=True, exist_ok=True)
    label_path.parent.mkdir(parents=True, exist_ok=True)
    image_path.write_bytes(image_bytes)
    label_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def export_coco(images: dict, boxes: dict, selected: list[int]) -> dict[str, int]:
    counts = defaultdict(int)
    for index, image_id in enumerate(selected, start=1):
        if index % 100 == 0:
            print(f"coco {index}/{len(selected)}", flush=True)
        image = images[image_id]
        lines = []
        for label, x, y, w, h in boxes[image_id]:
            x, y, w, h = clip_box(x, y, w, h, image["width"], image["height"])
            line = yolo_line(label, x, y, w, h, image["width"], image["height"])
            if line:
                lines.append(line)
                counts[NAMES[label]] += 1
        if not lines:
            continue
        url = f"http://images.cocodataset.org/{image['split']}/{image['file_name']}"
        dest = CACHE / "coco" / image["file_name"]
        fetch(url, dest)
        split = split_of(f"coco-{image_id}")
        write_sample(split, f"coco_{image_id}", dest.read_bytes(), lines)
        counts[split] += 1
    return counts


def photo_years(info: dict, title: str) -> list[int]:
    meta = info.get("extmetadata") or {}
    dated: list[int] = []
    for key in ("DateTimeOriginal", "DateTime"):
        dated.extend(int(year) for year in YEAR_RE.findall((meta.get(key) or {}).get("value", "")))
    if dated:
        return dated
    return [int(year) for year in YEAR_RE.findall(title)]


def commons_files(limit: int) -> list[dict]:
    found = []
    seen: set[str] = set()
    token = None
    # deepcategory stays inside trolleybus categories. A plain "trolleybus"
    # search also returns trams, museum cars, and pre-war photos.
    search = 'deepcategory:"Trolleybuses" filetype:bitmap -tram -streetcar -museum'
    while len(found) < limit:
        query = (
            "https://commons.wikimedia.org/w/api.php?action=query&format=json"
            "&generator=search&gsrnamespace=6&gsrlimit=50"
            "&gsrsearch=" + urllib.request.quote(search) +
            "&prop=imageinfo&iiprop=url|mime|size|extmetadata&iiurlwidth=960"
        )
        if token:
            query += "&gsroffset=" + str(token)
        request = urllib.request.Request(query, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = json.load(response)
        pages = payload.get("query", {}).get("pages", {})
        for page in pages.values():
            title = page.get("title", "")
            info = (page.get("imageinfo") or [None])[0]
            if not info or not str(info.get("mime", "")).startswith("image/"):
                continue
            if info.get("mime") == "image/svg+xml":
                continue
            thumb = info.get("thumburl") or ""
            if "thumb.wikimedia.org" not in thumb:
                continue
            if int(info.get("width") or 0) < 640 or int(info.get("height") or 0) < 480:
                continue
            lowered = title.lower()
            if any(word in lowered for word in ("tram", "streetcar", "museum", "fortepan", "bundesarchiv", "diagram", "logo")):
                continue
            years = photo_years(info, title)
            if years and min(years) < MIN_PHOTO_YEAR:
                continue
            meta = info.get("extmetadata") or {}
            license_name = (meta.get("LicenseShortName") or {}).get("value", "")
            if any(flag in license_name.upper() for flag in ("NC", "NONCOMMERCIAL")):
                continue
            if not any(flag in license_name.upper() for flag in ("CC", "PUBLIC DOMAIN", "CC0", "PDM")):
                continue
            if title in seen:
                continue
            seen.add(title)
            found.append({
                "title": title,
                "url": thumb,
                "license": license_name,
                "year": min(years) if years else None,
            })
            if len(found) >= limit:
                break
        token = payload.get("continue", {}).get("gsroffset")
        if not token:
            break
        time.sleep(0.2)
    return found


def autolabel_trolleybuses(model_path: Path) -> dict[str, int]:
    from ultralytics import YOLO

    model = YOLO(str(model_path))
    files = commons_files(COMMONS_TARGET)
    (CACHE / "commons_sources.json").write_text(json.dumps(files, indent=2), encoding="utf-8")
    counts = defaultdict(int)
    coco_name = {0: 2, 1: 7, 2: 5}  # our label -> COCO predict index: car, truck, bus
    print(f"commons candidates {len(files)}", flush=True)
    for index, item in enumerate(files):
        if index % 20 == 0:
            print(
                f"commons {index}/{len(files)} kept {counts['train'] + counts['val']}",
                flush=True,
            )
        dest = CACHE / "commons" / f"{index:04d}.jpg"
        try:
            fetch(item["url"], dest)
        except Exception as error:
            time.sleep(2.0)
            try:
                fetch(item["url"], dest)
            except Exception as retry_error:
                counts["download_errors"] += 1
                if counts["download_errors"] <= 5:
                    print(f"commons download failed: {retry_error}", flush=True)
                continue
        time.sleep(0.4)
        try:
            prediction = model.predict(source=str(dest), imgsz=640, conf=0.35, verbose=False)[0]
        except Exception as error:
            counts["predict_errors"] += 1
            if counts["predict_errors"] <= 5:
                print(f"commons predict failed: {error}", flush=True)
            continue
        height, width = prediction.orig_shape
        lines = []
        saw_trolleybus = False
        if prediction.boxes is None:
            continue
        for box, cls, conf in zip(prediction.boxes.xyxy.tolist(), prediction.boxes.cls.tolist(), prediction.boxes.conf.tolist()):
            coco_cls = int(cls)
            x1, y1, x2, y2 = box
            if coco_cls == coco_name[2] and conf >= 0.35:
                label = 3
                saw_trolleybus = True
            elif coco_cls == coco_name[0] and conf >= 0.45:
                label = 0
            elif coco_cls == coco_name[1] and conf >= 0.45:
                label = 1
            else:
                continue
            line = yolo_line(label, x1, y1, x2 - x1, y2 - y1, width, height)
            if line:
                lines.append(line)
                counts[NAMES[label]] += 1
        if not saw_trolleybus or not lines:
            continue
        split = split_of(item["title"])
        write_sample(split, f"commons_{index:04d}", dest.read_bytes(), lines)
        counts[split] += 1
    return counts


def write_yaml() -> None:
    text = (
        f"path: {DATA}\n"
        "train: images/train\n"
        "val: images/val\n"
        "names:\n"
        "  0: car\n"
        "  1: truck\n"
        "  2: bus\n"
        "  3: trolleybus\n"
    )
    (ROOT / "data.yaml").write_text(text, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", type=Path, default=ROOT / "yolov8n.pt")
    parser.add_argument("--commons-only", action="store_true")
    args = parser.parse_args()
    coco_counts = {}
    selected = []
    if not args.commons_only:
        images, boxes = load_coco()
        selected = choose_coco(images, boxes)
        coco_counts = export_coco(images, boxes, selected)
    if not args.weights.exists():
        fetch("https://github.com/ultralytics/assets/releases/download/v8.3.0/yolov8n.pt", args.weights)
    commons_counts = autolabel_trolleybuses(args.weights)
    write_yaml()
    summary = {"coco_selected_images": len(selected), "coco": coco_counts, "commons": commons_counts}
    (DATA / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
