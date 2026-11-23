#!/usr/bin/env python3
"""Export the trained nano head to the tracker vision tensor contract.

Android LiteRT wants one Float32 NHWC input [1,640,640,3] in RGB [0,1] and one
raw head. iOS Core ML wants one RGB image input that applies /255 itself and
one Float32 raw head. Both heads are [1,8,8400]: xywh in input pixels plus 4
already-activated class probabilities, no NMS and no Float16 I/O.
"""

from __future__ import annotations

import shutil
import types
from pathlib import Path

import torch
from ultralytics import YOLO
from ultralytics.nn.modules import Detect
from ultralytics.utils.export.coreml import torch2coreml
from ultralytics.utils.export.litert import _litert_gather, _litert_grouped_topk, torch2litert

ROOT = Path(__file__).resolve().parent
BEST = ROOT / "runs" / "uk4" / "weights" / "best.pt"
OUT = ROOT / "exports"
IMGSZ = 640


class RawHead(torch.nn.Module):
    """NCHW in, single decoded head out. Drops the training-feature dict."""

    def __init__(self, model: torch.nn.Module) -> None:
        super().__init__()
        self.inner = model

    def forward(self, images: torch.Tensor) -> torch.Tensor:
        prediction = self.inner(images)
        return prediction[0] if isinstance(prediction, (tuple, list)) else prediction


class NhwcRawHead(torch.nn.Module):
    """NHWC [1,H,W,3] in [0,1], single decoded head out."""

    def __init__(self, model: torch.nn.Module) -> None:
        super().__init__()
        self.inner = model

    def forward(self, images: torch.Tensor) -> torch.Tensor:
        prediction = self.inner(images.permute(0, 3, 1, 2))
        return prediction[0] if isinstance(prediction, (tuple, list)) else prediction


def load_fused() -> torch.nn.Module:
    yolo = YOLO(str(BEST))
    model = yolo.model
    model.eval()
    model.fuse()
    for module in model.modules():
        if isinstance(module, Detect):
            module._grouped_topk = _litert_grouped_topk
            module._gather = types.MethodType(_litert_gather, module)
    return model


def export_tflite(model: torch.nn.Module) -> Path:
    wrapper = NhwcRawHead(model).eval()
    dummy = torch.zeros(1, IMGSZ, IMGSZ, 3)
    # metadata has no detect task, so torch2litert does not divide xywh by imgsz
    produced = Path(
        torch2litert(
            wrapper,
            dummy,
            OUT / "uk4.pt",
            quantize=None,
            metadata={"names": {0: "car", 1: "truck", 2: "bus", 3: "trolleybus"}},
        )
    )
    destination = OUT / "uk4.tflite"
    if produced.resolve() != destination.resolve():
        produced.replace(destination)
    return destination


def export_coreml(model: torch.nn.Module) -> Path:
    import coremltools as ct

    wrapper = RawHead(model).eval()
    dummy = torch.zeros(1, 3, IMGSZ, IMGSZ)
    inputs = [ct.ImageType("image", shape=dummy.shape, scale=1 / 255, color_layout=ct.colorlayout.RGB)]
    package = OUT / "uk4.mlpackage"
    if package.exists():
        shutil.rmtree(package)
    converted = torch2coreml(
        model=wrapper,
        inputs=inputs,
        im=dummy,
        classifier_names=None,
        mlmodel=False,
        quantize=None,
        metadata={"names": "0 car, 1 truck, 2 bus, 3 trolleybus", "nms": False},
    )
    converted.save(str(package))
    compiled = OUT / "uk4.mlmodelc"
    if compiled.exists():
        shutil.rmtree(compiled)
    import subprocess

    subprocess.run(
        ["xcrun", "coremlcompiler", "compile", str(package), str(OUT)],
        check=True,
    )
    built = OUT / "uk4.mlmodelc"
    if not built.exists():
        # coremlcompiler names the bundle after the package stem
        candidates = list(OUT.glob("*.mlmodelc"))
        if len(candidates) != 1:
            raise SystemExit(f"compiled model not found in {OUT}")
        candidates[0].rename(built)
    return built


def main() -> None:
    if not BEST.exists():
        raise SystemExit(f"missing checkpoint: {BEST}")
    OUT.mkdir(parents=True, exist_ok=True)
    model = load_fused()
    tflite = export_tflite(model)
    coreml = export_coreml(model)
    note = OUT / "EXPORT.txt"
    note.write_text(
        "class order: 0 car, 1 truck, 2 bus, 3 trolleybus\n"
        "input: 640 letterbox, RGB [0,1]; Core ML image input applies /255\n"
        "output: raw YOLO head [1,8,8400], no NMS, xywh + 4 class probabilities\n"
        "BoxUnits: INPUT_PIXELS (xywh is in input pixels, not normalized)\n"
        "I/O: Float32. No int8 and no Float16 external tensors.\n"
        f"tflite: {tflite}\n"
        f"coreml: {coreml}\n",
        encoding="utf-8",
    )
    print(note.read_text())


if __name__ == "__main__":
    main()
