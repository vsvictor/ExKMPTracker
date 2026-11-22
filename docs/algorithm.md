# Tracking pipeline

[Українська](algorithm.uk.md)

The behavioral source is [tracking](tracking.md) and [traffic](traffic.md).
Changes since 1.0.0 are in the [changelog](changelog.md).
This project is an independent implementation, not a Flutter wrapper.
The rules below describe enabled section 1. See [CoopNav sections](coopnav.md)
for raw pass-through when section 1 is off and the optional cooperative branch.

## Trust and estimation

Native callbacks become `TrackerEvent`s. `TrackerBloc` processes them sequentially.
Network requests run outside the event loop; their completion carries a session
generation. A stale completion cannot acknowledge another owner's queue.

Only a nonempty authenticated session and both confirmed shift identity fields
enable tracking. The host must authenticate and obtain a successful `getShift`
**before** starting the native runtime. `performerId` maps to `driverId`.
Constructing `TrackingSession` does not itself authenticate or call `getShift`.
The host retains ownership of its existing authenticated RPC connection and supplies
`TrackingRpcTransport`; tracker calls are multiplexed through that same connection.

The four-state EKF uses east/north metres, speed and clockwise heading:

- GNSS/fused accuracy ≤ 65 m; network ≤ 150 m. Honest urban fixes stay in the
  filter. Their variance is the provider radius, with a 3 m floor, and network
  adds 30 m in quadrature so a cell/Wi-Fi radius cannot collapse the covariance.
- Fix age in monotonic time: `-2 < age ≤ 10` seconds. Native providers preserve
  observation age; arrival time is never substituted for observation time.
- Network fallback is suppressed for 10 seconds after an accepted precise fix.
  Android feeds one precise stream: fused location, or platform GPS only after
  fused location fails, or network only when neither precise provider starts.
- Android 68% horizontal radius converts to per-axis variance using
  `2.27886856637673`; reported uncertainty uses the largest covariance eigenvalue.
- Position innovations must pass the 2D 99.9% chi-square gate (`13.8155`).
  A single failure leaves the state unchanged. Three consecutive precise fixes
  that agree with each other reinitialize the filter, so a biased lock can be left.
- Speed/course accuracy uses provider values with conservative fallbacks.
  Course updates require speed > 0.5 m/s. Speed below 0.3 m/s is integrated;
  it is not forced to zero.
- Prediction uses ≤ 0.1 s steps, acceleration clamped to ±6 m/s², yaw to
  ±3 rad/s, Joseph covariance updates, and lower lateral noise in vehicle mode.
  Active native IMU requests target 50 Hz. The filter integrates timestamped control
  history between location fixes instead of applying the last sample to the whole
  interval; controls expire after one second without a fresh sample.
- Predictions stop at 15 seconds since the last real fix or uncertainty > 100 m.
  Missing/stale IMU falls back to constant velocity **with an `imuUnavailable`
  flag**, not invented acceleration.
  Quiet-motion magnitude remains usable without course calibration; unavailable
  navigation control components are null, not fabricated zero measurements.
- Walking advances 0.75 m per step, 1–10 steps per event, with a GNSS-calibrated
  compass offset and the same prediction limits. It bridges gaps > 2 seconds,
  rather than double-counting fresh GNSS displacement. Step events need a
  monotonic observation timestamp no older than 2 seconds; suspended backlogs
  must not be relabelled as fresh steps.
- Mock fixes and speed/long-gap teleportation > 220 km/h are rejected by default.
  AGC/CN0 and clock checks are heuristics, not proof of authenticity.
  Radio jumps set `SUSPECTED_INTERFERENCE` and `SUSPECTED_SPOOFING`; the earlier
  camel-case flag aliases remain present for compatibility.

The local tangent-plane conversion uses the specification's spherical Earth
(`R = 6371000 m`), not survey-grade geodesy. The origin recenters after a 2 km
move so the motion model stays in local metres. Polar regions still require
evaluation against reference data; no centimetre-level claim is made.
GNSS accuracy is a provider estimate, not independently measured error.
Map matching is an optional injected `MapMatcher` and is **off by default**.
No road graph or RTK correction service is bundled.

## Stationary behaviour

A confirmed stop repeats a fixed anchor at zero speed. Quiet IMU plus reported
GNSS speed < 0.5 m/s enters a hold. After a precise fix faster than 0.5 m/s,
one later slow sample does not: two consecutive slow precise fixes are required.
Speed > 1 m/s or a displacement > `max(20 m, 2.5 × accuracy)` exits the hold.
Network fixes cannot release a stop. Transient jolts cannot release the hold.
Five seconds of continuous IMU motion can release it only without a precise fix
during those five seconds.

The anchor can be replaced once from network to GNSS and refined once after
≥ 10 precise fixes using inverse-variance weights, a 2× improvement requirement
and a best-single-fix/4 accuracy floor. Stationary uncertainty grows 0.1 m/s
and the added growth is capped at 15 m. On Android, activity-based STILL pauses
precise sensors and raw GNSS, and slows location to a 5-second precise watchdog
instead of stopping it. iOS retains continuous Core Location: motion callbacks
alone cannot reliably wake a suspended app. Fixes received during STILL still
update the filter. The published position stays on the anchor until the hold
releases, and a release leaves STILL so the fast location stream resumes.
STILL received before the first fix is deferred until an anchor
exists, avoiding a cold-start deadlock.
STILL contradicted by a precise fix faster than 1 m/s from the last five seconds
is also deferred until it can be corroborated, rather than slowing GNSS during motion.
An expired position cannot be revived as a fresh stationary anchor. An explicit
STILL anchors the current filter estimate, not an older point skipped by the
recording throttle.

**Deliberate safety refinement:** quiet accelerometer readings alone do not
trigger zero velocity while the last reported GNSS speed indicates driving.
A car at constant speed has near-zero acceleration. ZUPT therefore also requires
corroborating low speed (or an explicit STILL transition). Gyro bias is learned
only from quiet samples with corroborated stationary evidence, so a gentle
driving turn is not calibrated away; pressure-rate veto prevents quiet vertical motion from
being mistaken for a stop when pressure data is available.

## Persistence and delivery

Recording follows speed. The configured 25 m / 10° / 30 s gates apply at 8 m/s.
At about 2 m/s and below they widen to 100 m / 30° / 120 s. At 20 m/s and above
they tighten to 10 m / 6° / 12 s. A start from under 1 m/s to at least 2 m/s,
a speed change of at least 5 m/s, a turn, a return from network/dead reckoning/
stationary to GPS or fused, the first stationary point, and a 40 m accuracy
worsening or a recovery to ≤ 25 m also record immediately. Ingest follows the
same scale: 30 s while slow, 10 s at 8 m/s, and 5 s at highway speed. The server
stores every accepted point and does not choose this cadence.
The throttle commits only after durable append succeeds. Prediction/stationary
points have null raw fields; network points omit speed/course via JSON null.
Identifiers contain 16 secure random bytes in lowercase hex.

Queues are partitioned by **both** `driverId` and `brandId` and use native
SQLCipher databases. Platform key protection, migration from the earlier
SQLite/protected-file queues and backup constraints are covered in
[storage](storage.md).

Every ≥ 10 seconds, upload at most 100 FIFO points. Server time is refreshed
after 60 seconds; references expire after 300 seconds. A flush has a 30-second
timeout. Errors remain visible in `TrackerState.error` and are logged; the next
interval retries without deleting data.

`acceptedIds` must contain unique members of the submitted batch. Only its
accepted FIFO prefix is removed atomically. An unaccepted head or malformed
response removes nothing. An unreadable record aborts delivery; corruption is
never treated as an empty queue. Delivery is at-least-once; the server **must**
deduplicate on point `id`. There is no queue-size cap or automatic eviction:
provision storage and monitor errors during extended offline use.

`RpcTrackingTransport` sends these business parameters through the host's existing
authenticated JSON-RPC connection (QUIC for phase one):

```json
{"jsonrpc":"2.0","id":1,"method":"tracking.ingest","params":{
  "sessionId":"authenticated-session","driverId":"performer-id","brandId":"brand-id",
  "points":[]
}}
```

Nonempty real batches are required by the implementation. Successful ingest
returns `{"acceptedIds":["point-id"]}` inside `result`.
`serverTime` takes `sessionId` and returns `{"serverTimeMs":123456789}`.
The host adds PASETO authentication parameters and owns the client, connection
lifecycle and TLS policy. See [CoopNav](coopnav.md).

## Operating-system limits

Foreground does not mean impossible to terminate. Explicit force-stop/force-quit,
revoked permissions, disabled location, battery restrictions, reboot, protected
data unavailability and vendor task killers can interrupt tracking. Neither
platform guarantees exact 1 Hz delivery or uninterrupted background execution.
iOS does not expose Android's GNSS raw measurements or app-wide UID traffic.
See the platform integration guides for permission and lifecycle requirements.

No background algorithm can recover the true vehicle route from phone IMU alone
through a long tunnel. Predictions are marked `deadReckoning`/`predicted`, and
stop rather than masquerading as GNSS observations. Section 2 can continue only
with fresh confirmed visual donors, labels its corrections `cooperative`, and
never changes the independent section-1 fallback.
