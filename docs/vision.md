# Phase 2: on-device vision

[Українською](vision.uk.md)

Add `implementation(project(":tracker-vision"))` to the host KMP module. Return `androidEdgeVision(...)` from `AndroidTrackerProvider.createEdgeVisionController(context)`, or pass `iosEdgeVision(...)` as `vision` to `IosTrackerRuntime`. Let the runtime manage `start`/`stop` through its Phase 2 gate. Merely adding the dependency does not enable Phase 2; Phase 3 stays off. The JVM target is for shared code/tests, not native inference.

For Swift, use JDK 21 to run `./gradlew :tracker-vision:assembleExKMPTrackerVisionReleaseXCFramework`. Link the resulting static `tracker-vision/build/XCFrameworks/release/ExKMPTrackerVision.xcframework` **instead of** the standalone tracker or HTTP framework. It includes iOS arm64 device and arm64 simulator slices and explicitly exports `:tracker`, coroutines, and serialization JSON. `HostRpcTrackingTransport` implements both tracking and cooperative transport; supply your `TrackingRpcClient` for networking.

```swift
import CoreML
import Foundation
import ExKMPTrackerVision

func makeTracker(client: any TrackingRpcClient, frames: any VisionFrameSource, modelURL: URL?) -> IosTrackerRuntime {
    // Illustrative values only: replace with measurements for the host camera.
    let calibration = CameraCalibration(imageWidth: 640, imageHeight: 480,
        fx: 400, fy: 400, cx: 319.5, cy: 239.5, cameraHeightMeters: 2,
        heightStdDevMeters: 0.1, contactStdDevPixels: 1, maxGroundRangeMeters: 80,
        minDepressionDegrees: 3)
    let vision = IosEdgeVisionKt.iosEdgeVision(compiledModelUrl: modelURL,
        source: frames, calibration: calibration,
        config: YoloConfig.companion.cocoVehicles(units: .inputPixels),
        timing: VisionTiming(maxFps: 30, maxFrameAgeSeconds: 0.5,
            maxPoseSkewSeconds: 0.05, estimateRadialSpeed: false),
        configurationFactory: {
            let config = MLModelConfiguration()
            config.computeUnits = .all
            return config
        })
    return IosTrackerRuntime(transport: HostRpcTrackingTransport(client: client),
        vision: vision, vehicleClass: .car)
}
```

In Swift, implement `VisionFrameSource.subscribe(onFrame:) -> any VisionFrameSubscription` and `VisionFrameSubscription.unsubscribe()`. Create `VisionFrame(width:height:rowStrideBytes:rgba:captureMonotonicSeconds:pose:poseMonotonicSeconds:)` with a `KotlinByteArray`, a `CameraPose` and `KotlinDouble(value: poseTime)` for the nullable pose timestamp.

On Android the factory is `androidEdgeVision(modelFile: File?, source: VisionFrameSource?, calibration: CameraCalibration?, config: YoloConfig, timing: VisionTiming = VisionTiming(), optionsFactory: () -> AndroidVisionOptions = …): EdgeVisionController`, in `com.exkmptracker.vision`.

The host provides a **licensed local model**, `VisionFrameSource`, `CameraCalibration`, capture-time `CameraPose`, and explicit `YoloConfig`. Cooperative networking and a host-supplied GTFS URL belong to tracker/server configuration, not this module. The module has no camera UI, permissions, model assets, model downloader, or network code. Missing model, calibration, or frame source reports unavailable without starting inference or capture. It never substitutes synthetic results.

## Model contract

| Backend | Accepted input | Accepted output |
|---|---|---|
| Android LiteRT | A local `.tflite` file, one Float32 `[1,H,W,3]` input (NHWC) | One Float32 raw YOLO tensor |
| iOS Core ML | A local compiled `.mlmodelc` URL; one Float32 RGB `CHW`/`HWC` array with optional singleton batch, or one RGB image input accepting a `32BGRA` buffer | One Float32 or Double raw YOLO multi-array |

Use an appropriate YOLOv8n/YOLO11n detection export with **fixed shapes**, one output in `[N,C]` or `[C,N]` (optional leading batch of one), and no embedded NMS. `C` is 84 for COCO or 8 for the four-class Ukrainian head. The four leading channels are center `x,y,width,height`; select `BoxUnits.INPUT_PIXELS` or `BoxUnits.NORMALIZED` for the **specific export**, since axes and units cannot be inferred reliably from values. The remaining channels must be class probabilities, already activated. Android and Core ML array inputs receive RGB in `[0,1]`; an image-input Core ML export must apply RGB `/255` itself, with no second resize/crop. Both paths use a bilinear letterbox with fill 114. Quantized external tensors, Float16 external tensors, logits, objectness columns, ambiguous square outputs, multi-head/segmentation exports, built-in NMS outputs and class counts other than 4 or 80 are not supported. Internal model quantization/precision is independent of the I/O types.

`YoloConfig.cocoVehicles(units)` explicitly maps zero-based COCO IDs 2→car, 7→truck and 5→bus. **COCO cannot distinguish trolleybuses.** Do not relabel the COCO bus class. `YoloConfig.ukrainianVehicles(units)` reads a 4-class head: 0 car, 1 truck, 2 bus, 3 trolleybus. `YoloConfig.classCount` must match the tensor head.

Android defaults to the bundled standalone LiteRT interpreter. On the inference thread it selects a GPU delegate when `CompatibilityList` says the device supports one, otherwise an NNAPI delegate that is not allowed to use the NNAPI reference CPU, otherwise four XNNPACK threads. The delegate is created and closed on that same thread, after the interpreter. If constructing the interpreter with the selected delegate throws, the default path releases it and retries once on four CPU threads. A host `optionsFactory` replaces this policy and is not retried. Pass `cpuAndroidVisionOptions()` to force CPU. iOS defaults to `MLComputeUnitsAll`; inject an `MLModelConfiguration` for another Core ML compute policy. Unsupported models or native failures surface as unavailable. Delegates may keep unsupported ops on CPU, so accelerator selection does not guarantee that the whole graph leaves the CPU.

## Camera lifecycle and calibration

- On Android, the host binds CameraX `ImageAnalysis` to a permitted foreground lifecycle (e.g. `STRATEGY_KEEP_ONLY_LATEST`). Convert/copy the image to upright, unmirrored, undistorted RGBA matching calibration; always close the `ImageProxy` in `finally`. On iOS, the host owns an `AVCaptureSession` and performs the equivalent conversion while correctly retaining/locking its `CVPixelBuffer`. `VisionFrame.rgba` is a detached, immutable byte array after publication; the host must not modify or recycle it, even after the callback returns. Pass the RGBA row stride in bytes.
- The source subscribes and unsubscribes off-main; marshal camera API calls to the appropriate host thread. Capture at exposure time in the tracker's monotonic epoch (Android `SystemClock.elapsedRealtimeNanos` in seconds, iOS `IosTrackerClock().monotonicSeconds()` using `CLOCK_MONOTONIC_RAW`, which includes sleep). Verify and convert camera PTS and pose timestamps to that epoch; do not pass `systemUptime`, `mach_absolute_time`, `CACurrentMediaTime()`, wall-clock time, or callback arrival as the capture time. Refresh the conversion after sleep or camera interruption. A pose must be synchronized to the frame, preferably interpolated. Missing, stale, future, mismatched and out-of-order inputs are dropped with rate-limited diagnostics. Diagnostics and results may arrive off-main; handlers should be thread-safe and return promptly.
- The module does not bypass camera permissions or background restrictions. Stop the host camera when OS/lifecycle policy requires it. Normal iOS background execution does not provide camera frames; Android background capture is subject to while-in-use permission/foreground service restrictions. Without frame arrivals, vision yields no evidence; raw GNSS + EKF still work. Do not retain or replay visual evidence through an interruption.
- Supply `fx,fy,cx,cy` for the exact published image dimensions, including any crop, resize, or orientation change. Use camera height above the **local level ground plane** and capture-time pitch-down, roll and true-north optical heading from the calibrated camera-to-vehicle/attitude transform. A device compass heading alone is not a camera pose. Supply realistic height, image contact, tilt and heading uncertainties; account for donor/environment effects in those values or withhold vision when the local plane is not valid. Steep slopes and uneven ground are not explicitly modeled.

Class-aware NMS and inverse letterboxing recover image boxes. Their bottom centers project onto the plane; no-intersection, near-horizon, cropped/unreliable and out-of-range candidates are dropped. Outputs contain at most eight observations per capture: horizontal ground distance in meters, absolute true-north azimuth clockwise in degrees, approximate variance (0.01–10,000 m²), and an optional signed range rate (positive when separating, bounded to ±122.3 m/s). Range rate is disabled by default; `VisionTiming(estimateRadialSpeed = true)` uses the change in camera-to-target range divided by capture-time difference for an unambiguous low-variance same-class pair. Each frame uses its own calibrated orientation; there is no independent ego-translation compensation, absolute target velocity, or stable identity. Occlusion, turns, poor extrinsics and detector/support-point error can bias estimates; there is no 3 m guarantee.

The controller processes one frame at a time off-main, keeps only the latest pending frame, throttles to at most 30 FPS, and drops late results. `stop()` invalidates the run immediately; unsubscribe and native release complete on the worker once any non-interruptible prediction returns. A callback already in progress cannot be revoked. **15–30 FPS is a device-dependent target, not a measured result**; measure time from exposure to evidence, sustained throughput, thermals and ground-truth range error on target hardware with the actual licensed exports. No frames are stored or transmitted by this module.

Verification (JDK 21): `./gradlew :tracker-vision:compileAndroidMain :tracker-vision:compileKotlinIosArm64 :tracker-vision:jvmTest :tracker-vision:iosSimulatorArm64Test`. These tests cover the shared processing contract, not model execution on physical devices.
