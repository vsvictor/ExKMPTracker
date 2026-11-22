# ExKMPTracker 1.1.5

[Українська](README.uk.md)

Changes since 1.0.0 are listed in the [changelog](docs/changelog.md).

Standalone Kotlin Multiplatform **vehicle tracking library** for Android and
iOS. Android runs location tracking in a native location Foreground Service;
iOS uses background Core Location automotive navigation. The shared EKF,
validation, stop holding, recording and FIFO delivery are managed by BLoC.
The tracker runs independently of the host's UI while the OS permits execution.

This is a library, not a map application or an independently installable APK.
No backend address, credentials or confirmed driver identity is bundled.
This delivery implements **CoopNav phases 1 and 2** from `XRNavi/docs/Req.md`.
Defaults are `section(1, true)`, `section(2, false)`, `section(3, false)`.
Phase 2 requires explicitly injected vision/model/calibration and server configuration;
MoQ offload (phase 3) is not implemented and cannot be enabled.

## Features

- High-accuracy native location, Doppler speed/course uncertainty and
  attitude-aware motion input; optional activity/step sensors.
- Four-state EKF with Joseph covariance updates, chi-square outlier rejection,
  stationary drift suppression and bounded, explicitly labelled dead reckoning.
- Durable protected offline queue, owner partitioning, 16-byte secure IDs,
  at-least-once JSON-RPC delivery and exact FIFO-prefix acknowledgements.
- Clock consistency and mock/teleport checks; Android GNSS interference
  heuristics when hardware provides the necessary measurements.
- Dependency-injected transport, clock, persistence, logger and optional road
  matcher; observable `TrackerState`. No global runtime singleton.
- Android UID traffic counters since runtime-counter creation; unsupported
  iOS app-wide traffic counting fails explicitly.
- Optional calibrated native YOLO vision, authenticated peer synchronization and
  gated cooperative EKF correction with an uncontaminated live autonomous fallback.
  See [section controls](docs/coopnav.md) and [vision configuration](docs/vision.md).

## Integration

1. Authenticate and complete `getShift` in the host, without starting sensors.
2. Obtain precise location permissions with a user-visible explanation.
3. Construct the session **only from the confirmed server response**:

```kotlin
val session = TrackingSession.fromConfirmedShift(
    sessionId = authenticatedSessionId,
    performerId = shift.performerId,
    brandId = shift.brandId,
)
```

4. Configure the native dependency graph and start from the visible host.
   The native runtime retains sensor ownership when the UI is backgrounded.
5. Observe `TrackerState` and surface its `error`/`rejection` to the user.
   Explicitly stop tracking at shift end.

See the complete [Android integration](docs/android.md),
[iOS integration](docs/ios.md) and [algorithm/protocol](docs/algorithm.md).
Use `RpcTrackingTransport(hostRpc)` with the host's `TrackingRpcTransport(call/notify)`
adapter over its existing authenticated JSON-RPC-2-Client-KMP connection. The core
opens no network connections. Swift hosts can implement `TrackingRpcClient` and pass
`HostRpcTrackingTransport(client:)` to the native runtime. See the
[CoopNav contract](docs/coopnav.md) and [SQLCipher storage](docs/storage.md).
The former Ktor adapters moved to the separate, optional `:tracker-http` module;
they are not used by the CoopNav core or required for its integration.

To consume from another Gradle project, add this composite build to its
`settings.gradle.kts`:

```kotlin
includeBuild("/Users/victor/ExKMPTracker")
```

Then add `implementation("com.exkmptracker:tracker:1.1.5")` in the appropriate
KMP source set or Android module. Match the Kotlin/AGP toolchain in this build.
For Swift, build and link the XCFramework described in the iOS guide.

## Build

Requirements: JDK 21, Android SDK 37, and Xcode with iOS SDKs for Apple targets.
The wrapper uses Gradle 9.5.1. Configure your own SDK path in
`local.properties` (`sdk.dir=...`) or `ANDROID_HOME`.

```sh
./gradlew :tracker:jvmTest
./gradlew :tracker:assembleAndroidMain
./gradlew :tracker:assembleExKMPTrackerReleaseXCFramework
```

`commonTest` contains deterministic numerical, validation, throttling,
stationary, session-race, timeout and delivery-contract tests. Tests live in
separate `tests.kt` files, not implementation files. Hardware accuracy and
background longevity need reference-route and real-device evaluation.

## Accuracy and background limits

There is **no guaranteed metre-level accuracy** in poor satellite visibility,
urban canyons, spoofing or long tunnels. The tracker exposes uncertainty,
rejects poor inputs and stops predictions after 15 seconds or 100 m
uncertainty. A filter cannot create missing GNSS information. Road snapping
and RTK corrections are not enabled.

Permissions, the persistent Android notification and iOS background-mode
configuration are mandatory. Force-stop/force-quit, system restrictions or
disabled location can stop tracking. No platform guarantees exactly 1 Hz or
uninterrupted execution. Background location must be disclosed to users and
meet the host application's store/privacy requirements.
