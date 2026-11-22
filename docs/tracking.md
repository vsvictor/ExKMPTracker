# Tracking mechanism

[Українською](tracking.uk.md) | **English**

This document describes the ExKMPTracker 1.1.0 positioning pipeline: sources,
estimation, predictions, and the path of a point from a native event to server
acknowledgement. It is a Kotlin Multiplatform implementation, not a Flutter wrapper.
The short contract is in [algorithm.md](algorithm.md).

Key files:

- `tracker/src/commonMain/kotlin/com/exkmptracker/bloc/TrackerBloc.kt` — orchestration.
- `tracker/src/commonMain/kotlin/com/exkmptracker/filter/` — EKF, stop anchor, throttle.
- `tracker/src/androidMain/.../AndroidLocationSource.kt` — one precise location stream.
- `tracker/src/iosMain/.../IosLocationSource.kt` — Core Location.

## 1. Pipeline overview

```
 Native sensors                    TrackerBloc                      Backend
 ─────────────────                 ───────────────────────────      ──────────────
 one location stream ──────┐
   fused, else GPS,        │   validation:              EKF fix/predict
   else network ───────────┼─▶ age ≤ 10 s        ──▶   [E, N, v, ψ]   ──▶ TrackEstimate
 accelerometer/gyroscope ──┤   clock agreement         Joseph update
 magnetometer ─────────────┤   accuracy gates          χ² and unstick          │
 step counter ─────────────┤   anti-fake checks                               ▼
 activity transitions ─────┘                                     TrackThrottle
 GNSS measurements                                                (speed, turn, gap)
 (AGC, C/N0, clock)                                                        │
                                                                           ▼
                                                        encrypted SQLCipher queue (FIFO)
                                                                          │
                                                    flush every ≥ 10 s, batch ≤ 100
                                                                          ▼
                                                    JSON-RPC `tracking.ingest` ──▶ FIFO ack
```

BLoC events serialize every state change. `TrackerEvent.Tick` fires once per second.
Each native callback enters the bloc as an event.

The host authenticates and obtains a successful `getShift` before starting the native
runtime. `TrackingSession.fromConfirmedShift` takes `sessionId`, `performerId` and
`brandId` only from that confirmed response. The session constructor does not log in
and does not call `getShift`. `performerId` becomes `driverId`. An empty or
unconfirmed session does not start sensors. The identifiers are stored in the encrypted
session, partition the SQLCipher queue by the `brandId`/`driverId` pair, and accompany
every `tracking.ingest` as `driverId` and `brandId`. The host keeps ownership of the
authenticated RPC connection and supplies `TrackingRpcTransport`. A running tracker
with the same identifiers keeps running. Changing either identifier stops the previous
session before the new one starts. Android may restore a previously confirmed session
after process death when the host allows it.

Cooperative vision (phase 2) is described in [vision.md](vision.md). It does not change
the autonomous EKF until section 2 is enabled. Changes relative to 1.0.0 are in
[changelog.md](changelog.md).

## 2. Data sources

| Source | Origin | Role |
|---|---|---|
| `fused` | Fused Location, high accuracy, 1 Hz while moving, wait for an accurate fix | The only primary stream while the fused request succeeds |
| `gps` | `GPS_PROVIDER`, same interval | Only after fused fails. Doppler, course, mock |
| `network` | `NETWORK_PROVIDER` | Only when neither fused nor GPS started. Ignored for 10 s after a precise fix |
| `deadReckoning` | EKF prediction / pedestrian dead reckoning | Bridges GNSS gaps of at most 15 s |
| `stationary` | STILL anchor or held stop point (section 5.3) | Repeats a fixed point while the vehicle is stopped |
| IMU | Accelerometer + gyroscope | Longitudinal acceleration and yaw rate are EKF control inputs |
| Magnetometer | With the gravity vector, a tilt-compensated compass | Course for pedestrian dead reckoning |
| Step counter | Hardware step detector | Position updates from steps while walking |
| Activity recognition | STILL / WALKING / IN_VEHICLE transitions | Power saving and pedestrian dead reckoning |
| GNSS measurements | AGC, C/N0, clock (`utcTimeMs`, bias uncertainty) | Interference/spoof hints and the time check |

The passive provider is not read: another app's fix is not a second independent measurement.
The moving interval is 1 s. In `STILL`, Android keeps a precise watchdog every 5 s and does
not turn location off. iOS does not stop Core Location. On iOS the source is `gps` when the
OS is older than 14, or full-accuracy authorization is granted, and the radius is within the
GNSS gate; otherwise the fix is labelled `fused`.

A fix carries latitude, longitude, the 68% radius, provider time, monotonic time, and the
mock flag. When the platform provides them, it also carries speed, course and their
accuracies (on Android, speed and course accuracy require API 26+). Those accuracies enter
the filter (section 4.4). An invalid fix is rejected and does not stop the tracker.

## 3. Validation before the filter

Every incoming position passes, in order:

1. **Age** — `now − position.timestamp` must lie in `(−2 s, 10 s]`. Stale or future fixes
   are rejected.
2. **STILL hold** — the fix is not dropped. The published position stays on the anchor until
   speed or displacement releases the hold. A release leaves STILL.
3. **Network redundancy** — while a GNSS/fused fix was accepted within the last **10 s**, a
   network fix is counted in statistics but not processed, so it cannot pull a precise
   estimate sideways.
4. **Clock agreement** (not applied to `network` fixes, which already use a network clock) —
   see section 7. A GNSS fix whose satellite clock disagrees with the server reference is
   rejected.
5. **Filter input** (`TrackingFilter.fix`):
   - every number is finite; `|lat| ≤ 90`, `|lon| ≤ 180`; `accuracy > 0`;
   - **source accuracy gate**: GNSS/fused ≤ **65 m**, network ≤ **150 m**;
   - `speed ≥ −1` (−1 means unknown); course is −1 or lies in `[0, 360)`.
6. **Anti-fake checks** (when `antiFake` is on):
   - mock locations are rejected;
   - speed above 220 km/h is rejected;
   - after a signal gap longer than 15 s, the implied speed between the last fix and the new
     one — `(distance − accuracy − 100 m) / Δt` — must not exceed 220 km/h (the teleport check).
7. **Time monotonicity** — a fix with `monotonicSeconds ≤` the filter's last update time is
   rejected as stale.
8. **χ² outlier gate** — see section 4.6.

Each rejection sets a human-readable `rejection` shown in tracker status.

## 4. Extended Kalman filter

### 4.1 State and coordinates

The state is `x = [E, N, v, ψ]`:

- `E`, `N` — east/north displacement in metres on a local tangent plane. The origin moves
  after a 2 km displacement, so a long route does not stay on the plane of the start;
- `v` — ground speed in m/s, clamped at zero from below;
- `ψ` — course in radians, clockwise from north, wrapped into `(−π, π]`.

Geodetic conversion uses a spherical Earth, `R = 6 371 000 m`:

```
E = Δλ · R · cos(φ₀)      N = Δφ · R
```

If more than 15 s pass without a fix, the next accepted fix reinitializes the filter. It
still has to pass the teleport check against the last fix.

### 4.2 Accuracy and variance

Android reports accuracy as the **68% horizontal radius** of a two-dimensional Gaussian.
For a symmetric 2D Gaussian the radius that contains 68% of the probability mass satisfies
`r² = χ²₂(0.68) · σ²`, where `χ²₂(0.68) = 2.27886856637673`. Therefore:

- per-axis measurement variance is `σ² = σ_eff² / 2.27886856637673`;
- `σ_eff = max(3 m, accuracy)` for GNSS/fused. For network, 30 m is added in quadrature
  first: an optimistic cell or Wi-Fi radius cannot collapse the covariance;
- the filter's reported accuracy (section 4.7) is the inverse of the same 68% convention.

### 4.3 Initialization

On the first accepted fix:

```
x = [0, 0, max(0, speed), radians(heading)]
P = diag(σ²_meas, σ²_meas, σ²_v, σ²_ψ)
```

`σ²_v` is the reported speed variance (or 25 when speed is unknown). `σ²_ψ` is the reported
course variance (or 1 rad² when course is unknown).

### 4.4 Measured speed and course accuracy

Chipset Doppler accuracies are used as reported, with conservative fallbacks:

```
σ²_v = max(speedAccuracy², 0.01)                 when reported and finite, else 4.0 (m/s)²
σ²_ψ = max(radians(headingAccuracy)², 0.0003)    when reported, finite and < 180°, else 0.1 rad²
```

An invalid report never rejects the position. It only widens the variance.

### 4.5 Prediction

Between updates the filter integrates a constant-yaw-rate unicycle with a commanded
acceleration, in steps of **at most 0.1 s**:

```
E ← E + v·sin ψ·dt          v ← max(0, v + a·dt),   a = IMU longitudinal acceleration, clamped to ±6 m/s²
N ← N + v·cos ψ·dt          ψ ← wrap(ψ + ω·dt),      ω = gyroscope yaw rate, clamped to ±3 rad/s
```

The Jacobian `F` linearizes that motion. `P ← F P Fᵀ + Q·dt` with **process noise per
second** `Q = diag(1 m², 1 m², 1 (m/s)², 0.02 rad²)`. Acceleration and yaw rate are
*measured* by the IMU. The residual unmodelled dynamics are about 1 m/s² in speed and
about 8°/s in course, plus 1 m²/s of position model error.

`TrackerBloc` forms the IMU inputs. Gravity is a low-pass estimate. Longitudinal
acceleration is gravity-compensated acceleration projected onto the horizontal course.
Yaw rate is the gyroscope component about the gravity axis. Controls older than one second
are not applied across the whole interval. Missing or stale IMU falls back to constant
velocity and raises `imuUnavailable`. It does not invent an acceleration.

### 4.6 Measurement update

The position innovation must pass a **Mahalanobis gate** on the 2D position block. With
innovation covariance `S = P₂ₓ₂ + σ²_meas·I`:

```
d² = Δᵀ S⁻¹ Δ   must satisfy   d² ≤ 13.8155   (χ²₂ at 99.9%)
```

A single miss is rejected as an implausible jump and the state, including the tangent
origin, is restored. Three consecutive precise fixes within `max(40 m, 2 × accuracy)` of
the previous rejected fix reinitialize the filter. That leaves a biased lock. A network
fix does not reinitialize. Accepted measurements are sequential scalar updates (east,
north, then speed when known, then course when `v > 0.5 m/s`) in **Joseph form**:

```
K = P H ᵀ / (H P Hᵀ + σ²)
x ← x + K·residual          (the course residual is wrapped as an angle)
P ← (I − K H) P (I − K H)ᵀ + K σ² Kᵀ
```

Joseph form keeps `P` positive semi-definite despite floating-point error. Course is
corrected above 0.5 m/s, so walking does not keep a stale course. Speed below 0.3 m/s is
integrated and is not forced to zero.

### 4.7 Reported accuracy

Reported smoothed accuracy is the conservative 68% horizontal radius from the **largest
eigenvalue** of the 2×2 position covariance:

```
λ_max = (tr + √((P₀₀−P₁₁)² + 4·P₀₁²)) / 2
accuracy = √(2.27886856637673 · λ_max)
```

Using the largest eigenvalue, the worst direction, keeps the radius meaningful for an
elongated error ellipse. Inside `estimate()`, a displacement of at least 2 km recenters
the tangent plane. Latitude is converted with the old origin. The projection stays
invertible. A rejected fix restores the previous origin because it was snapshotted.

## 5. Predictions

### 5.1 Vehicle dead reckoning

When no fix arrives for more than 2 s, and the user is not walking, each
`TrackerEvent.Tick` calls `predict()`. The process model integrates forward with the
latest IMU acceleration and yaw rate. Limits:

- a hard **15 s** bound from the last fix;
- the estimate is withheld as soon as its accuracy exceeds
  **`maxEstimateAccuracyMeters = 100 m`**.

With the calibrated process noise, uncertainty grows slowly enough that the 15 s window
is usually usable (from a 5 m fix: about 7 m after 3 s, about 14 m after 6 s).

### 5.2 Pedestrian dead reckoning

While the activity is WALKING, GNSS prediction is replaced by step updates:

- hardware step events arrive in batches of **1–10 steps**;
- each step advances the position by **0.75 m** along the tilt-compensated compass course,
  corrected by `compassOffset` in `TrackerBloc`. The offset is calibrated from the GNSS
  course whenever the user walks faster than 0.7 m/s with a valid GNSS course;
- model speed is set to zero (the steps carry the displacement), and each step
  conservatively adds `stride² = 0.5625 m²` to both position variances;
- the same 15 s / 100 m limits apply. Steps bridge gaps longer than 2 s. A step event
  needs a monotonic observation time no older than 2 s.

### 5.3 Stationary behaviour

An explicit STILL pins filter speed at 0 (`stationary()`: a `v = 0` update with variance
0.01) and makes the current estimate a **stationary anchor**. On Android, precise sensors
and raw GNSS pause, but location remains a precise watchdog every 5 s. iOS does not stop
Core Location. Each tick republishes the anchor. Uncertainty grows by **0.1 m/s**, and
the added growth is capped at **15 m**. Three consecutive accelerometer samples whose
amplitude delta exceeds 0.8 m/s² wake the precise sensors. A fix during STILL updates the
filter. If the hold is no longer active, activity becomes UNKNOWN and the fast location
stream returns.

**Stop hold before STILL.** While the IMU is quiet (≥ 10 samples below 0.2 m/s²) and GNSS
speed confirms a stop, the parked fix becomes a held point: each fix and tick queues the
same point at speed 0. After a precise fix faster than **0.5 m/s**, one later slow sample
is not enough: two consecutive slow precise fixes are required. The first fix of a session,
with no prior motion, may start the hold immediately. A network anchor is replaced once by
the first GNSS/fused fix. Exit: speed **> 1 m/s** or displacement
**> max(20 m, 2.5 × accuracy)**. Network fixes never end a stop. Raw fixes are still
measured for parking-drift statistics.

The held point is then refined **once** to the inverse-variance weighted mean (weight
1/accuracy²) of accepted non-network fixes — after at least **10** such fixes, and only
when the mean is at least twice as precise as the published point. The mean is not trusted
beyond a quarter of the best single fix. A parked point therefore moves at most twice
(network replaced by GNSS, then one refinement). Refinement does not require the fix
accuracy to be ≤ 20 m. Network fixes cannot refine the anchor.

Short IMU jolts do not clear the hold. It also ends after **5 s** of continuous IMU motion
if those 5 s contained no precise fix. A stop between fixes keeps the last queued point,
so dead reckoning is not recorded while parked.

Quiet accelerometer readings alone do not trigger zero velocity while the last reported
GNSS speed indicates driving. A car at constant speed has near-zero acceleration. An
explicit STILL still anchors immediately. STILL contradicted by a precise fix faster than
1 m/s in the last 5 s is deferred.

### 5.4 Drift limits

Dead reckoning drifts because small IMU errors integrate over time. Five active mechanisms,
plus one disabled hook, bound that drift:

1. **ZUPT (zero-velocity update).** The accelerometer listener counts consecutive quiet
   samples (|linear acceleration| < 0.2 m/s²). After **10** quiet samples (about 2 s) —
   and only during dead reckoning (> 2 s since the last fix, not STILL, not walking) —
   filter speed is pinned at 0 through `stationary()`, which stops a creeping position.
   After real motion, ZUPT also requires the two-sample slow-speed confirmation above.
2. **Slow motion is not zeroed.** Speed below 0.3 m/s stays in the filter state. A stop
   is decided by the anchor and ZUPT, not by a forced zero during integration.
3. **Non-holonomic constraint.** When the activity is IN_VEHICLE (`constrainLateral = true`),
   position process noise becomes anisotropic: 1 m²/s along the course, but only
   **0.1 m²/s** across it. A car does not slide sideways. Cross-track GNSS noise is trusted
   less, and the track stays on the direction of motion.
4. **Gyroscope bias calibration.** While quiet, raw yaw rate feeds an EMA
   (`bias = 0.98·bias + 0.02·raw`, only when |raw − bias| < 0.05 rad/s). The bias is
   subtracted from later readings, so course does not spin itself during dead reckoning.
   Bias is learned only with corroborated stationary evidence.
5. **Barometer veto.** Fresh pressure from the platform barometer (Android Sensor, iOS
   `CMAltimeter`), when present, blocks ZUPT when |dP/dt| ≥ 0.02 hPa/s. In a lift the
   device is quiet on the accelerometer but moving vertically. Without a barometer the
   check stays IMU-only.
6. **Map matching (off).** `MapMatcher` is the injected port
   `com.exkmptracker.ports.MapMatcher`. No implementation is connected by default. The
   library contains no road graph and does not snap an estimate to a road. There is no
   RTK correction.

## 6. Spoofing and interference hints

- **Mock rejection** and **speed/teleport checks** — section 3.
- **AGC / C/N0 monitoring** (native GNSS measurements): a sudden AGC drop with a large
  carrier-to-noise change (`ΔAGC < −6 dB` and `|ΔC/N0| > 10 dB-Hz`) marks later points with
  `suspectedInterference` / `suspectedSpoofing`. The `SUSPECTED_*` aliases are also set.
  These are heuristics, not proof.
- **GNSS time check** — section 7. A fix whose satellite time disagrees with the server
  clock is rejected immediately.
- Active flags are stored on each queued point (`flags`), so the backend sees the device
  trust assessment next to each coordinate. iOS does not expose raw GNSS AGC or receiver
  clock measurements, and the runtime does not invent them.

## 7. Time checks

Two clocks are compared with the device:

1. **Server reference.** During flush, when the reference is older than 60 s, the bloc
   calls `serverTime` and stores a `TimeReference`: server time, the monotonic midpoint of
   the request, and an uncertainty equal to half the RTT. The reference is valid for 300 s.
   If the device wall clock disagrees by more than `2000 ms + uncertainty`,
   `deviceTimeMismatch` is set. Points are still recorded: the server can correct the stamps.
2. **GNSS clock.** The native layer reports satellite `utcTimeMs` and its bias uncertainty.
   If a fresh GNSS time (≤ 5 s old) disagrees with the extrapolated server reference by more
   than `2000 ms + network uncertainty + GNSS uncertainty`, `TimeGuard` rejects the fix.
   Without a fresh GNSS time, `gnssTimeUnverified` is set instead. Network-provider fixes
   skip this check. iOS has no raw GNSS clock measurements, so that path stays unverified.

## 8. Recording: throttle and point model

An accepted estimate is recorded only when `TrackThrottle.shouldRecord` passes.
The configured 25 m / 10° / 30 s gates are the city reference at **8 m/s**. The scale
is `8 / max(speed, 1)`, clamped to 0.4..4, and each gate is clamped again:

| Speed | Distance | Turn | Longest gap |
|---|---|---|---|
| ≤ 2 m/s | 100 m | 30°, and only above 0.5 m/s | 120 s |
| 4 m/s | 50 m | 20° above 1 m/s | 60 s |
| 8 m/s | 25 m | 10° above 1 m/s | 30 s |
| ≥ 20 m/s | 10 m | 6° above 1 m/s | 12 s |

Distance uses haversine. These rows are the default `TrackerConfig`; a host value
is multiplied by the same scale, then clamped to 8–100 m, 6–30°, and 5–120 s.

A point is also recorded immediately when:

- speed rises from under 1 m/s to at least 2 m/s (departure);
- speed changes by at least 5 m/s (brake or acceleration);
- course changes by the turn gate above;
- a `gps` or `fused` fix follows `network`, `deadReckoning`, or `stationary`;
- the source becomes `stationary` (the parking point, then the slow gap);
- accuracy worsens by at least 40 m, or improves by at least 25 m and is now ≤ 25 m.

The throttle commits its state (`recorded`) only **after** the point is durably appended
to the encrypted SQLCipher queue, so a storage failure does not silently drop the point.

Queued point contents:

| Field | Meaning |
|---|---|
| `id` | 16 random bytes in hex — the idempotency key |
| `rawLatitude` / `rawLongitude` / `rawAccuracyMeters` | Provider fix; `null` for predicted and stationary points |
| `smoothedLatitude` / `smoothedLongitude` / `smoothedAccuracyMeters` | EKF output (the same 68% convention) |
| `speedMps`, `bearingDegrees` | EKF speed and course; omitted for `network` points |
| `deviceTimeMs` | Device wall clock at append time |
| `observedAtMs` | Observation time of the fix |
| `source` | `fused` / `gps` / `network` / `deadReckoning` / `stationary` |
| `isMock` | Mock flag of the last trusted fix |
| `flags` | Active trust flags (`deviceTimeMismatch`, `gnssTimeUnverified`, `networkFallback`, `suspectedInterference`, …) |

## 9. Delivery: flush and acknowledgement

Each tick schedules one request outside the event handler when the speed-scaled flush
interval has elapsed. With the default config that is **30 s** at 2 m/s and below,
**10 s** at 8 m/s, and **5 s** from 20 m/s up, clamped to 5–30 s. The result returns
as `Message.Flushed`. The timeout is 30 s. An empty queue does not call
`tracking.ingest`. The server stores every accepted point and does not thin or
retime the track. Flush:

1. refreshes the server time reference when it is older than 60 s (section 7);
2. reads a FIFO batch of at most **100** points from the encrypted queue; any unreadable
   record aborts the flush — records are never deleted silently;
3. calls `tracking.ingest` with `sessionId`, `driverId`, `brandId` and `points`;
4. validates the response: `acceptedIds` must be a subset of the submitted ids;
5. acknowledges only the **FIFO prefix** of accepted points. If the server did not accept
   the head, nothing is acknowledged and the flush fails. The server receives points in
   order. Delivery is at-least-once, so the server deduplicates on `id`;
6. if the session or owner changed during transmission, the result is discarded.

Delivery errors stay visible in tracker status and are retried on the next flush interval.
The queue survives process restarts and offline periods. There is no size cap and no
automatic eviction.

## 10. Server contract

The host JSON-RPC accepts the point schema above. `tracking.ingest` deduplicates on `id`
and returns `{acceptedIds: [...]}` inside `result`. `serverTime` takes `{sessionId}` and
returns a positive `{serverTimeMs}`. The host adds authentication parameters. The delivery
contract is described in [algorithm.md](algorithm.md) and [coopnav.md](coopnav.md).

## 11. Constants

| Constant | Value | Where |
|---|---|---|
| GNSS/fused accuracy gate | 65 m | `maxGnssAccuracyMeters` |
| Network accuracy gate | 150 m | `maxNetworkAccuracyMeters` |
| Position σ floor | 3 m | `positionVariance` |
| Extra network σ | 30 m in quadrature | `positionVariance` |
| Tangent-plane recenter | 2 km | `recenterTangentPlane` |
| Prediction quality limit | 100 m | `maxEstimateAccuracyMeters` |
| Prediction horizon | 15 s | `fix` / `predict` / `pedestrianSteps` |
| 68% ↔ variance factor | 2.27886856637673 | `CHI_68` |
| Outlier gate (χ²₂, 99.9%) | 13.8155 | `CHI_SQUARE_GATE` |
| χ² cluster radius | max(40 m, 2 × accuracy), 3 fixes | `noteRejectedCluster` |
| Process noise / s | 1 m², 1 m², 1 (m/s)², 0.02 rad² | `TrackingFilter` |
| Fallback speed variance | 4 (m/s)² | `speedVariance` |
| Fallback course variance | 0.1 rad² | `headingVariance` |
| Integration step | ≤ 0.1 s | `TrackingFilter` |
| Maximum plausible speed | 220 km/h | `MAX_SPEED` |
| ZUPT quiet threshold | 10 samples × < 0.2 m/s² | `TrackerBloc.quietMotion` |
| Minimum course-update speed | > 0.5 m/s | `MIN_COURSE_UPDATE_SPEED_MPS` |
| Course age for longitudinal acceleration | 3 s | `AndroidSensorController`, `IosAcceptedCourseReference` |
| Lateral NHC noise / s | 0.1 m² | vehicle-mode process noise |
| Gyro bias EMA | 0.98/0.02, gate 0.05 rad/s | `TrackerBloc` |
| Barometer veto | \|dP/dt\| ≥ 0.02 hPa/s | `TrackerBloc` |
| Step length | 0.75 m | `pedestrianSteps` |
| Stationary accuracy growth | 0.1 m/s, added growth ≤ 15 m | `StopAnchor` |
| Stop hold speed enter / exit | < 0.5 / > 1 m/s; after motion, 2 slow fixes | `TrackerBloc`, `StopAnchor` |
| Stop hold displacement exit | > max(20 m, 2.5 × accuracy) | `StopAnchor` |
| STILL location watchdog | 5 s, high accuracy | `AndroidLocationSource` |
| Stop hold IMU-motion reset | 5 s without a GNSS/fused fix | `TrackerBloc` |
| Stop-point refinement | once, ≥ 10 non-network fixes, ≥ 2× tighter, floor best/4 | `StopAnchor` |
| Network redundancy window | 10 s after a GNSS/fused fix | `TrackerBloc` |
| Throttle at 8 m/s | 25 m / 10° / 30 s | `TrackThrottle` |
| Throttle at ≤ 2 m/s | 100 m / 30° / 120 s | `TrackThrottle` |
| Throttle at ≥ 20 m/s | 10 m / 6° / 12 s | `TrackThrottle` |
| Departure / speed step | < 1 → ≥ 2 m/s, or \|Δv\| ≥ 5 m/s | `TrackThrottle` |
| Fix age window | −2…+10 s | `TrackerBloc` |
| Clock disagreement tolerance | 2000 ms + uncertainties | `TimeGuard` |
| Flush interval / batch / timeout | 5–30 s by speed / ≤ 100 points / 30 s | `TrackerBloc` |
| `serverTime` refresh | every 60 s, valid for 300 s | `TimeGuard` |
