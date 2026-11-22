# iOS integration

`IosTrackerRuntime` is the native lifecycle adapter for the shared tracker. It owns
CoreLocation/CoreMotion lifetimes, a one-second best-effort `Tick`, and the default
protected queue/session stores. It is instance-scoped: create it with an application-owned
`CoroutineScope` and your authenticated `TrackingTransport`; do not keep a process singleton.
The host remains responsible for creating a `TrackingSession` only after a user-confirmed
shift and for deciding when to restore a saved session.

## Build and link

On macOS with Xcode and the required Kotlin/Native toolchain installed:

```sh
./gradlew :tracker:assembleExKMPTrackerXCFramework
```

Add the resulting `ExKMPTracker.xcframework` to the Xcode app target (or distribute it as a
binary framework). Link the Apple `CoreLocation`, `CoreMotion`, `Foundation`, and `Security`
frameworks if Xcode does not add the required system frameworks automatically. The framework
is static and is configured for `iosArm64` and `iosSimulatorArm64`.

Implement `TrackingRpcClient` over the host's existing authenticated RPC connection.
No new HTTP client or connection is created. See the [CoopNav contract](coopnav.md):

```swift
let transport = HostRpcTrackingTransport(client: hostRpcClient)
let runtime = IosTrackerRuntime(transport: transport)

let identity = try TrackingIdentity(driverId: driverID, brandId: brandID)
let session = try TrackingSession(sessionId: confirmedSessionID, identity: identity)
// Only after Info.plist contains UIBackgroundModes=location plus both location purpose
// strings, and the host app itself has obtained Always (and Precise) authorization:
try await runtime.startConfirmedSession(
    session: session,
    backgroundLocationConfigured: true
)

let stateObservation = runtime.observeState { snapshot in
    render(snapshot)
}
```

`IosTrackerRuntime(transport:)` is an exact one-argument initializer (Kotlin default arguments
are not exported to Swift). `IosTrackerRuntime(transport:config:logger:storage:)` is the explicit
Swift alternative. Both own their coroutine scope. `startConfirmedSession(session:)` without the
flag is foreground-only.

Start, restore, stop and shutdown are serialized per runtime instance. Calling
`startConfirmedSession` while running with the same driver/brand identity updates the saved and
BLoC session without restarting CoreLocation/CoreMotion (a changed background flag is re-applied
to the running location manager). A different identity first fully stops the old session,
including clearing its saved session, and only then starts the new one; if that stop fails the
error is thrown and the new session is not started.

`restoreSavedSession(backgroundLocationConfigured:)` is deliberately explicit; constructing a runtime never resumes a
persisted session. `stop()` ends the session and clears its saved identity, but leaves queued
track points intact. Stop teardown is unconditional: the tick timer, CoreLocation and
CoreMotion callbacks and the BLoC session stop in guaranteed cleanup even if a step fails. If
clearing the saved session fails, tracking is still stopped, the failure is logged and reported
in `TrackerState.error`, and `stop()` then throws it to Swift. Before deleting the session file,
`stop()` durably writes a stop marker; while that marker exists `restoreSavedSession()`
returns `nil`, so a stopped session is never resumed on a later launch. Only a new
`startConfirmedSession` replaces the marker. `shutdown()` stops native resources and the BLoC but retains the saved
session for a later explicit restore; the injected scope remains owned by the host. Do not call
`shutdown()` merely because the app entered the background. The BLoC's
`StateFlow<TrackerState>` is exposed directly as `runtime.state`; `observeState` is a
Swift-friendly callback over that same flow and returns an `IosStateObservation` handle with a
parameterless `cancel()`. The owner of each handle must cancel it explicitly. For example,
cancel the observation and stop/shut down the runtime:

```swift
stateObservation.cancel()
try await runtime.stop()       // omit if preserving a saved session for explicit restore
try await runtime.shutdown()
```

The Swift initializers own their coroutine scope and cancel it during `shutdown()`.
Kotlin hosts can instead pass their own scope to the primary constructor; that scope remains
host-owned, and `shutdown()` cancels and joins only the runtime's internal course-reference
observer so a long-lived host scope does not retain the runtime. The runtime never closes an
arbitrary injected transport. The Swift observation callback returns `Void`.
The callback and `runtime.state` observe the BLoC's flow; neither creates another tracker-state
copy.

`HostRpcTrackingTransport` bridges JSON-result callbacks from the host connection and propagates
cancellation through `TrackingRpcCall.cancel()`. The host injects the current PASETO token into
RPC parameters and owns its refresh policy and connection lifetime. The former
`IosJsonRpcTrackingTransport` is available only in the optional legacy `:tracker-http` module.
Kotlin suspend APIs annotated with `@Throws` are
exported as throwing Swift async/completion-handler APIs; use `try await` (or the generated
completion-handler signature) for `startConfirmedSession`, `restoreSavedSession`, `stop`, and
`shutdown`. Consult the generated `ExKMPTracker.h` for exact signatures.

## Permissions and host configuration

The library never requests permissions automatically. The host should explain the feature and
request access in its own user flow. Include localized purpose strings; translate these values
for every supported app language:

```xml
<key>NSLocationWhenInUseUsageDescription</key>
<string>Location is used to record your confirmed trip.</string>
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>Location is used to record your confirmed trip while the app is in the background.</string>
<key>NSMotionUsageDescription</key>
<string>Motion and step data help improve trip estimates.</string>
```

For continuous background location, also add the `location` background mode in the app target
(equivalent Info.plist form):

```xml
<key>UIBackgroundModes</key>
<array>
    <string>location</string>
</array>
```

Only pass `backgroundLocationConfigured = true` when that mode and the Always-location purpose
string are present. The runtime verifies both Info.plist entries and enables
`allowsBackgroundLocationUpdates` only when CoreLocation also reports Always authorization.
With When In Use authorization, tracking is foreground-only. The app must separately handle
authorization and Precise Location in its UI/settings flow: reduced accuracy cannot be
upgraded by this library, and raw GNSS evidence remains unavailable even with Precise enabled.
The runtime does not call either authorization request API.

The manager requests best-for-navigation accuracy, no distance filter, automotive-navigation
activity, and disables automatic pausing. These settings are requests, not guarantees: iOS may
throttle or suspend execution. The one-second timer is best-effort and does not run reliably
while the process is suspended; CoreLocation callbacks can still provide location updates while
eligible background execution is granted. There is no exact 1 Hz, relaunch-after-force-quit,
or force-quit recovery guarantee. The host must explicitly call `restoreSavedSession()` when
its own product policy permits restoration.

## Native data and limitations

- Valid, recent `CLLocation` values become `LocationFix` events. Samples older than 30 seconds,
  more than five seconds in the future, or with invalid coordinates/accuracy are rejected.
  Their monotonic timestamp is the current `TrackerClock` time minus the sample's age measured
  against `NSDate`.
- A fix is labelled `gps` only when horizontal accuracy meets `maxGnssAccuracyMeters` and iOS
  reports Full Accuracy. Before iOS 14 that authorization flag does not exist, so a precise
  sample is labelled `gps`. Otherwise the source is `fused`. That label is not a precision
  guarantee: Core Location can still report a 50–65 m radius under Full Accuracy, and iOS
  has no separate network provider. Such a sample is coarse. For 10 seconds after a fix of
  ≤ 25 m it is ignored, and for 60 seconds it cannot reinitialize the filter. Speed/course
  and their accuracy values are passed only when CoreLocation reports them as valid.
  The value drawn by the map is the filter radius, not this sample's `horizontalAccuracy`.
- `isSimulatedBySoftware` is checked on iOS 15 and later. On older iOS versions the value is
  conservatively `false` because the platform does not expose that signal; `false` is not proof
  that a sample is genuine.
- CoreMotion requests 50 Hz active device-motion updates; actual delivery is OS-dependent.
  Acceleration is gravity-free and converted from g to m/s². The
  adapter prefers a true-north, vertical attitude frame and falls back to a magnetic-north,
  vertical frame only when available. It rotates acceleration and angular velocity into that
  world frame and projects horizontal acceleration onto an accepted course reference; it does
  not treat an arbitrarily oriented phone axis as vehicle-forward. In the magnetic fallback it
  waits for CoreLocation's true/magnetic heading correction before emitting longitudinal
  samples. Yaw rate is the negative of right-handed angular velocity about world-up, matching
  the tracker's clockwise-from-north heading convention. Missing course/north calibration
  makes `longitudinalAcceleration` `null` (reported once), rather than a guessed value; the
  genuine linear-acceleration magnitude and the world-up yaw rate (which needs only the vertical
  attitude and gyroscope) are still emitted, so the core can detect quiet/parked periods on a
  cold start.
  The course reference is derived only from the BLoC's accepted `state.estimate` (via the
  runtime's state observer), never from raw CoreLocation callbacks, so mock, stale, poor or
  rejected fixes cannot calibrate the motion frame. A moving (≥1 m/s) `gps`/`fused` estimate
  with a valid bearing is the anchor and keeps its accepted observation time; dead-reckoned
  estimates may supply the current heading only within 3 s of that anchor. Without a fresh
  anchor, only the longitudinal control is withheld (`null`) and a diagnostic is reported.
  Activity classification and pedometer updates are emitted only when the corresponding
  CoreMotion service is available.
- `compassDegrees` carries a validated `CLHeading` from the same `CLLocationManager`:
  `trueHeading`, or `magneticHeading` plus the last CoreLocation-derived true/magnetic
  declination when `trueHeading` is not yet valid. Headings with negative or >45° accuracy,
  older than 1 s, or not observed within 1 s of the motion sample time are omitted (`null`);
  no heading is ever invented. This is what makes the core's walking step prediction usable;
  without a compass it is rejected. `CLHeading` is the device top-edge heading, so the core's
  compass offset still applies.
- `pressureHpa` comes from `CMAltimeter` when the barometer is available (kPa × 10 = hPa).
  Each fresh reading (≤1 s old, resume-aware) is attached once to the next motion sample within
  1 s, so the core's pressure-rate stop veto sees real changes rather than repeated values.
  Without a barometer the field stays `null` and `PlatformError` is reported once.
- While the BLoC marks the device stationary and pauses continuous device-motion and pedometer
  updates, a low-rate (2 Hz) accelerometer wake source remains active, and activity recognition
  and the barometer keep running so genuine walking/vehicle transitions still resume sensors.
  Each wake sample is sent as a `MotionSample` whose magnitude is `‖a − g_ref‖` in m/s², where
  `g_ref` is the device-frame gravity *vector* retained from the last device-motion sample
  (≤5 s old; otherwise the first wake sample, with a diagnostic). `g_ref` is refined only by
  confirmed-quiet samples (<0.2 m/s²), so it never chases a real sustained acceleration. A
  horizontal 2 m/s² start therefore reads ≈2 m/s² (the norm-only `|‖a‖ − g|` would read
  ≈0.2 m/s²). Navigation controls are `null` (never fabricated zeros). The shared core counts three consecutive samples above 0.8 m/s² and calls
  `setStationary(false)`, which restarts continuous CoreMotion; it does not itself claim walking
  or driving.
- Deliberate iOS difference from Android: STILL does **not** stop Core Location. CoreMotion
  accelerometer/activity updates alone do not reliably wake or resume a suspended iOS app, so
  stopping location while stationary could end background tracking. Continuous location updates
  (`pausesLocationUpdatesAutomatically = false`) keep running for the whole confirmed session;
  only continuous device-motion/pedometer is reduced to the low-rate wake accelerometer (activity
  recognition and barometer stay on), and the BLoC keeps accepting location fixes. The published
  position stays on the stop anchor until speed or displacement releases it. This favors accuracy and background continuity
  over battery saving. The three-sample accelerometer wake is guaranteed only while the process
  is executing; it is not a wake-up mechanism after OS suspension. Significant-change location
  monitoring could at most aid coarse recovery and is not a replacement for that wake check.
  Device/simulator availability and permission can vary; unavailable sensors are exposed by
  `sensorCapabilities` and reported as `PlatformError`.
- iOS does not expose raw GNSS receiver clock measurements or AGC. The runtime emits no
  fabricated `Gnss` evidence. iOS also does not expose total network traffic for the app UID;
  `IosTrafficCounter` throws `UnsupportedOperationException` rather than returning invented
  zeroes.
- `TrackerClock` monotonic time is Darwin `clock_gettime(CLOCK_MONOTONIC_RAW)`, which keeps
  advancing while the device sleeps and is not affected by wall-clock changes, so 15 s
  predictions and 300 s references expire across suspension. Wall time comes from `NSDate`.
  CoreMotion timestamps use sleep-excluding uptime; their age relative to
  `NSProcessInfo.systemUptime` is converted into the monotonic domain, samples older than one
  second are dropped, and the first samples after a detected sleep gap are discarded until fresh
  motion timestamps arrive. A `CLLocation` age measured against `NSDate` can be distorted by a
  wall-clock change during the sample's short lifetime; stale/future samples are rejected.

## Persistence

Storage is dependency-injected: an `IosProtectedStorage(directory:)` instance (default:
`Application Support/com.exkmptracker`) owns an instance mutex, and `IosTrackQueue(storage:)` and
`IosSessionStore(storage:)` receive it. Every public queue/session operation is one transaction
under that mutex; locks are never nested (runtime lifecycle lock, then storage lock, never the
reverse). There is no global storage singleton. Create exactly one storage instance per
directory per process and share it with every queue/session store and runtime using that
directory; independent instances on the same directory are not coordinated and could overwrite
each other's snapshots. The runtime's default parameters create one storage instance shared by
its own queue and session store, so two runtimes constructed with defaults in one process must
instead be given a shared storage instance (or different directories).

`IosTrackQueue` now stores FIFO rows in a SQLCipher database, with a random key in
Keychain (`AfterFirstUnlockThisDeviceOnly`), full synchronous commits and exact-prefix
transactional acknowledgements. Existing protected JSON snapshots migrate per identity,
with an atomic migration marker preventing re-import after a crash. Corruption or key
loss fails explicitly without deleting data. See [storage](storage.md) for native
linking, stable key identifiers, migration and backup requirements.

`IosSessionStore` uses the same protected OS store for the confirmed session rather than
Keychain (a documented, accepted deviation from a Keychain-backed store). Its contents are protected after the device's first unlock and are not accessible
before that unlock. Session restoration remains opt-in. The session ID is persisted in this
protected store; any additional bearer token is not. The injected transport remains responsible
for protecting its own authentication credentials.
