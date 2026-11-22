# Changelog

[Українською](changelog.uk.md) | **English**

## 1.1.1

The map circle is the filter radius, not the latest `CLLocation.horizontalAccuracy`.
On iOS a 50–65 m sample is still labelled `gps` because there is no network source
and the acceptance gate is 65 m. In 1.1.0 three such samples, or one of them after
a 15-second gap, could reinitialize a tight estimate, so a circle could jump from
about 3 m to about 59 m. A separate `CLLocationManager` is a different sample and
is not expected to match.

1.1.1 keeps a fix of ≤ 25 m as the precise lock. A coarser fix is ignored for 10
seconds after it and cannot reinitialize the filter for 60 seconds. Chi-square
reinitialization requires that same precise accuracy. Reported radius does not go
below 3 m. While precise fixes continue, the stationary circle does not grow; the
0.1 m/s growth, capped at 15 m, starts only after the last precise fix. The held
position can still receive a new timestamp so the pin does not expire. That
timestamp is not a new measurement.

## 1.1.0

Compared with library **1.0.0** and with the Flutter TMSDriver specification that
`docs/tracking.uk.md` and `docs/traffic.uk.md` originally copied. The library was
already Kotlin Multiplatform in 1.0.0. This release changes filter, stop and vision
behaviour. It does not rewrite the tracker in Rust.

### Position acceptance

| Behaviour | 1.0.0 | 1.1.0 |
|---|---|---|
| Default GNSS/fused gate | 20 m | 65 m (`TrackerConfig.maxGnssAccuracyMeters`) |
| Default network gate | 50 m | 150 m (`TrackerConfig.maxNetworkAccuracyMeters`) |
| Position variance | `accuracy² / CHI_68` | `σ = max(3 m, hypot(accuracy, 30 m if network))`, then `σ² / CHI_68` |
| χ² miss | State restored, origin not snapshotted | State and tangent origin restored |
| Repeated χ² misses | Stay rejected | Three precise fixes within `max(40 m, 2 × accuracy)` of the previous rejected fix reinitialize the filter. Network does not |
| Course update | Speed > 2 m/s | Speed > 0.5 m/s |
| Speed below 0.3 m/s | Forced to zero when acceleration is also small | Integrated, not snapped to zero |
| Tangent plane | Fixed at initialization | Recenters after `hypot(east, north) ≥ 2000 m`. The conversion uses the old origin latitude and stays invertible |
| Course age for longitudinal acceleration | 15 s | 3 s on Android and iOS |

Hosts that construct `TrackerConfig` with their own accuracy gates keep those gates.
The 65 m and 150 m values are the new defaults. Reported accuracy is still the largest
position-covariance eigenvalue converted back through `CHI_68 = 2.27886856637673`.
The 99.9% gate remains `13.8155`.

### Stop hold and STILL

| Behaviour | 1.0.0 | 1.1.0 |
|---|---|---|
| Release speed | > 1.5 m/s | > 1.0 m/s |
| Release displacement | Accuracy ≤ 20 m and moved > `max(50 m, 3 × accuracy)` | Moved > `max(20 m, 2.5 × accuracy)`. Network never releases |
| Uncertainty growth | 0.1 m/s, unbounded | 0.1 m/s, added growth capped at 15 m |
| Anchor refinement | Required accuracy ≤ 20 m | Any non-network fix may contribute. Network still cannot refine |
| Auto-ZUPT after motion | One slow precise sample | After a precise fix ≥ 0.5 m/s, two consecutive slow precise fixes. The first fix of a session can still anchor |
| Fixes during STILL | Rejected as "Stationary activity: GNSS paused" | Accepted. If the hold is not active afterwards, activity becomes UNKNOWN and `setStationary(false)` resumes the fast stream |
| STILL contradicted by a recent precise fix | Deferred above 1.5 m/s in the last 5 s | Deferred above 1.0 m/s in the last 5 s |
| Android location in STILL | Stopped with the precise sensors | Precise sensors and raw GNSS still pause. Location continues as a 5 s high-accuracy watchdog |

An explicit STILL still anchors immediately. Quiet IMU alone still does not zero
velocity while GNSS speed indicates driving. The barometer veto is unchanged.

### Android location stream

1.0.0 could consume more than one provider, including passive updates. 1.1.0 keeps
one stream:

- fused high accuracy with `setWaitForAccurateLocation(true)` first;
- `GPS_PROVIDER` only if the fused request fails or throws;
- network only if neither precise provider starts, plus a `PlatformError` that network
  is not a precise fallback;
- the passive provider is not read;
- moving interval 1 s, stationary watchdog 5 s;
- a generation counter prevents a late failure from tearing down a newer request.

Network fixes are still ignored for 10 s after an accepted precise fix, so a cell or
Wi-Fi point is not a second measurement in the EKF.

### iOS source label

On iOS 14 and later, a fix is `gps` only with full-accuracy authorization and a radius
inside the GNSS gate. 1.0.0 also required `iosMajorVersion() >= 14`, so older systems
were never labelled `gps`. 1.1.0 treats iOS before 14 as full accuracy, because that
OS has no reduced-accuracy switch. Core Location is still not stopped in STILL.
`MLComputeUnitsAll` is unchanged.

### On-device vision

1.0.0 built the Android interpreter with four threads and no delegate.
1.1.0, on the default `androidEdgeVision()` path:

- uses the bundled LiteRT 1.4.1 interpreter together with `litert-gpu` and `litert-gpu-api`;
- selects a GPU delegate when `CompatibilityList` says the device supports one;
- otherwise an NNAPI delegate with FP16 allowed, the NNAPI reference CPU disabled, and
  sustained-speed preference;
- otherwise four XNNPACK threads;
- if that interpreter constructor throws, releases the delegate and retries once on CPU.
  The fallback is not reported as `onError` or a vision failure;
- a host `optionsFactory` replaces this policy and is not retried. `cpuAndroidVisionOptions()`
  forces CPU. `acceleratedAndroidVisionOptions()` exposes the GPU, then NNAPI, then CPU choice.

Delegates may leave unsupported ops on CPU. The runtime does not download a model or a
delegate: the AARs are in the app, and the `.tflite` file is supplied by the host.
Play Services TFLite is not used. Letterboxing is unchanged. iOS still uses
`MLComputeUnitsAll` unless the host injects another `MLModelConfiguration`.

### Documentation

`docs/tracking.md` and `docs/tracking.uk.md` describe this pipeline. They no longer
describe a Flutter UI isolate, a Dart `MapMatcher`, `sensors_plus`, or Room.
`docs/traffic.md` and `docs/traffic.uk.md` describe `TrafficCounter`: an Android UID
baseline taken at counter creation, and an iOS method that throws. This repository
has no statistics screen.

### Send cadence

1.0.0 recorded a point every 25 m, every 10° while faster than 1 m/s, or every 30 s,
and flushed the queue every 10 s. 1.1.0 scales those configured gates with speed.
They still apply unchanged at 8 m/s. At 2 m/s and below the default schedule is
100 m, 30°, and 120 s. At 20 m/s and above it is 10 m, 6°, and 12 s. A point is
also kept at once for a departure (under 1 m/s to at least 2 m/s), a speed change
of at least 5 m/s, the speed-scaled turn, the first precise fix after network,
dead reckoning or a stop, the first stationary point, and a 40 m accuracy drop or
a recovery to 25 m or better.

The ingest attempt uses the same scale: 30 s while slow, 10 s at 8 m/s, and 5 s
at highway speed. An empty queue still does not call `tracking.ingest`. ExServerTracker
accepts and stores every valid point. It has no speed, no minimum spacing and no
way to slow the phone down before the point is sent, so the cadence stays on the
client. The batch cap remains 100 points.

### Unchanged

- Prediction horizon 15 s and uncertainty limit 100 m.
- No bundled RTK service and no road graph. `MapMatcher` stays off unless the host injects one.
- SQLCipher queue, owner partitioning, and exact FIFO-prefix acknowledgement.
- No Rust rewrite of the filter or the platform adapters.
