# CoopNav phases 1 and 2

[Українська](coopnav.uk.md)

The scope includes autonomous EKF and opt-in cooperative positioning from
`XRNavi/docs/Req.md`. MoQ, video encoding and GPU offload remain unimplemented.
The optional [native vision module](vision.md) requires host-supplied licensed
model assets, calibrated optics/extrinsics and timestamped camera frames.

## Section controls

`CoopNavTracker`, `TrackerBloc`, both native runtimes and the Android service
binder expose the suspending `section(number, enabled)` API. It completes after
the BLoC serializes the transition; observe `state.sections` and
`state.cooperativeStatus`/`state.error`. Defaults on every new runtime are:

```kotlin
tracker.section(1, true)
tracker.section(2, false)
tracker.section(3, false)
// After injecting a configured vision controller and enabling the server:
tracker.section(2, true)
```

Disabling section 2 cancels synchronization, stops vision and discards late
callbacks/results. Output immediately returns to the **live independent EKF**,
not the last corrected coordinate. Corrections run on isolated filter branches;
neither future GNSS filtering nor IMU course calibration uses cooperative output.
With fresh GNSS, requests branch from the autonomous filter. During a GNSS gap,
an independent cooperative branch continues from confirmed donors. Its covariance
has a measurement-error floor, so correlated frames cannot average away shared
donor/calibration bias.

Disabling section 1 disables section 2 too, resumes native positioning and passes
subsequent valid native fixes through unchanged; EKF, recording and upload stop.
Re-enabling section 1 resets its filter, but does not re-enable section 2.
Section 2 requires section 1 and injected cooperative transport + vision; missing
dependencies fail explicitly. Enabling section 3 throws an unsupported-stage error.
Switches are runtime-local, not persisted across process death or sent to the
backend. The server host separately enables its stage 2 and configures GTFS feeds;
an end-user cannot enable fleet-wide processing via RPC.
GTFS is optional for V2V: a server with no configured feed still matches vehicles,
does not start a feed worker, and exposes `gtfs-unconfigured` to its host.

Android hosts supply `createEdgeVisionController(context)` on
`AndroidTrackerProvider` and return `RpcTrackingTransport(hostRpc)` as before.
Swift hosts can use `IosTrackerRuntime(transport:vision:vehicleClass:)` with
`HostRpcTrackingTransport`, which supports both RPC contracts. These transports
share the host connection and never open or close a second connection.

## Cooperative contract

The client uses authenticated **QUIC**, with permission `coop.sync_peers`, at
at most 10 Hz, one in-flight request, 500 ms timeout. Native inference has its
own frame-rate budget; the network never sends video. Business parameters:

```json
{"sessionId":"s","driverId":"d","brandId":"b","captureTimestampMs":1700000000100,
 "state":{"latitude":50.0,"longitude":30.0,"accuracyMeters":5.0,
   "speedMps":10.0,"bearingDegrees":90.0,"trustScore":0.9,
   "vehicleClass":"car","flags":[]},
 "observedPeers":[{"observationId":"v1","vehicleClass":"bus",
   "distanceMeters":20.0,"azimuthDegrees":90.0,"relativeSpeedMps":null,
   "visionVarianceMeters2":4.0}]}
```

`azimuthDegrees` is clockwise from **true north**, not the image/device axis;
distance is horizontal metres. Vision variance is conservative per-axis m²
including calibration and angle uncertainty. Classes are `car`, `truck`, `bus`,
`trolleybus`; a standard COCO bus prediction cannot prove trolleybus identity.
Optional `relativeSpeedMps` is range rate: positive away from the ego camera,
negative approaching, null when association or timing cannot support it.
No more than eight observations or three uniquely assigned donors are accepted.
Result is `{captureTimestampMs, matchedDonors:[...]}`; each donor contains
`observationId, donorId, latitude, longitude, accuracyMeters, speedMps,
bearingDegrees, trustScore, vehicleClass, captureTimestampMs`.
Only geometrically confirmed, trusted, fresh, same-brand donors are usable.

Capture UTC is derived from authenticated server time and the monotonic clock,
not device wall time. Reference age must be ≤60 s, uncertainty ≤100 ms; frames
must be ≤300 ms old when scheduled and results ≤500 ms old when applied.
Responses older than the current autonomous estimate are discarded, not
relabelled as current. Errors remain visible and fall back without blocking GNSS.
Absent camera observations still allow publishing an independent vehicle state;
they never create synthetic visual measurements.

The filter subtracts the calibrated camera vector from the donor position,
sums donor/vision/time uncertainty, gates at χ²=13.8155 and uses Joseph covariance
updates. Donor trust ≥0.7 is required. Only fresh independent GNSS/fused estimates
can advertise client donor trust; cooperative output is never a trusted donor
feedback loop. Trust/flags remain heuristics, not cryptographic proof of GNSS.
Fresh confirmed donors can sustain the cooperative branch beyond the autonomous
GNSS prediction horizon. Without another confirmed donor, that branch expires
too; corrections older than 500 ms fall back on the next 100 ms scheduler tick,
rather than labelling prediction as a fresh cooperative fix. There is no cold
start without an initial usable native origin.
GTFS supplies server-side anchors, not a local road map.

Corrections are labelled `cooperative`; queued corrected points have null raw
fields. Accuracy 3–5 m, inference 15–30 FPS, ≤5 ms server processing and 60–80 ms
RTT are **measurement targets, not verified guarantees**. Without calibrated
model assets, live anchors and actual camera frames, stage 2 cannot refine a fix.
Background location continues, but normal iOS apps cannot continuously capture
the camera in the background; Android camera capture requires the host's own
camera permissions/foreground-service policy. Camera suspension falls back to EKF.

## Host-owned connection

The `:tracker` module contains no Ktor client, endpoint or network engine.
Implement `TrackingRpcTransport` over the application's existing
JSON-RPC-2-Client-KMP connection:

```kotlin
class FleetRpc(
    private val callResult: suspend (String, JsonObject) -> JsonObject,
    private val sendNotification: suspend (String, JsonObject) -> Unit,
    private val currentAccessToken: suspend () -> String,
) : TrackingRpcTransport {
    private suspend fun authenticated(params: JsonObject) = buildJsonObject {
        params.forEach { (key, value) -> put(key, value) }
        put("access_token", currentAccessToken())
    }

    override suspend fun call(method: String, params: JsonObject): JsonObject =
        callResult(method, authenticated(params))

    override suspend fun notify(method: String, params: JsonObject) =
        sendNotification(method, authenticated(params))
}

val transport = RpcTrackingTransport(hostRpc)
```

The function arguments above adapt the host client's existing call/notify
methods; they are not a second RPC implementation. `callResult` must return only
the decoded `result` object and throw on JSON-RPC `error`. The host validates
envelopes/request IDs and owns TLS certificate verification, authentication,
token refresh, multiplexing and reconnection. It must propagate cancellation
and avoid including tokens or backend error data in exception messages.
Use **QUIC** for tracker calls; do not send bearer tokens over plaintext
UDP/TCP/RUDP. Tracking uses acknowledged `call`, never fire-and-forget `notify`.

On Android return `RpcTrackingTransport(hostRpc)` from
`AndroidTrackerProvider.createTrackingTransport`. Keep the host connection
available for the lifetime of the Foreground Service, not just an Activity.
On iOS pass that adapter to `IosTrackerRuntime` from Kotlin, or implement the
Swift-friendly `TrackingRpcClient` protocol and pass
`HostRpcTrackingTransport(client:)`. Its callback receives JSON **result**
strings or a `TrackingRpcFailure(code:)`; its returned `TrackingRpcCall.cancel()`
must cancel the host's pending request. Duplicate/late callbacks are ignored.
Neither runtime closes the injected connection.

`CoopNavTracker` is a common-code facade taking `TrackingRpcTransport`,
`TrackQueueStorage`, `PlatformSensorProvider` and the existing injected ports.
It exposes the same BLoC state/events, without a second state store.
Native runtimes retain permission, sensor, background and persistence ownership.
Existing `TrackQueue`, `SensorController`, `TrackingTransport` and `TrackerBloc`
remain supported; the first two extend the new storage/sensor contracts.

## Delivery and authorization

`serverTime` receives `{sessionId}` and returns a positive integer
`{serverTimeMs}`. `tracking.ingest` receives
`{sessionId, driverId, brandId, points}` and returns `{acceptedIds}`.
The host adds `access_token` or `authorization: "Bearer …"` to parameters,
not both. A session ID is an opaque confirmed-shift identifier, **not**
a substitute for a PASETO access token.
Required RBAC permissions are `tracking.ingest` and `tracking.server_time`.

The backend must authorize the authenticated principal against that confirmed
session and its driver/brand. Merely constructing `TrackingSession` does not
grant access. Configure the plugin's trusted session-authorizer dependency in
the server host; never accept a driver's client-provided IDs as proof of ownership.

At most 100 points are sent per call, at least ten seconds between flushes.
The client deletes only the accepted FIFO prefix in one storage transaction;
timeouts, authentication errors and malformed acknowledgements preserve data.
Delivery is at least once: after a lost response the same IDs are resent.
Redis durability and deduplication retention must be configured according to
the server instructions in `/Users/victor/ExServerTracker/README.md`.
In-memory server storage is development-only and cannot provide crash durability.

## Storage and migration

See [SQLCipher storage](storage.md) for native key management and migration.
Do not delete an existing queue, its encryption keys or app data when upgrading.
Storage errors are explicit, not converted into an empty queue.

The previous HTTPS helpers are now optional compatibility code in
`:tracker-http`. A host using those classes must add
`implementation("com.exkmptracker:tracker-http:1.0.0")`; Swift hosts build
`:tracker-http:assembleExKMPTrackerHttpReleaseXCFramework` and link that
framework **instead of** the core framework, not alongside it. They are not the
transport for this CoopNav backend, whose AuthPlugin expects credentials in
RPC parameters. New integrations should use the existing host connection.
