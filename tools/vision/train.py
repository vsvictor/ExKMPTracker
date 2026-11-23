#!/usr/bin/env python3
"""Fine-tune one nano detector. Does not change the Kotlin vision runtime."""

from pathlib import Path

from ultralytics import YOLO

ROOT = Path(__file__).resolve().parent


def main() -> None:
    model = YOLO(str(ROOT / "yolov8n.pt"))
    model.train(
        data=str(ROOT / "data.yaml"),
        epochs=30,
        imgsz=640,
        batch=8,
        device="mps",
        workers=0,
        amp=False,
        cache=False,
        patience=8,
        project=str(ROOT / "runs"),
        name="uk4",
        exist_ok=True,
        pretrained=True,
        mosaic=1.0,
        close_mosaic=5,
        plots=False,
        save=True,
    )


if __name__ == "__main__":
    main()
