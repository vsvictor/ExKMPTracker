# Android integration

The Android source set supplies the foreground-service, location/sensor adapters, encrypted
session store and FIFO queue. The library deliberately has no default server or application
singleton: the host provides an authenticated `TrackingTransport` and starts tracking only after
the user confirms a shift.

## Host setup and permission flow

The merged library manifest declares the location foreground service and the required
`FINE`, `COARSE`, `BACKGROUND`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`,
`ACTIVITY_RECOGNITION`, `POST_NOTIFICATIONS`, `INTERNET`, and `WAKE_LOCK` permissions. The host
application must implement `AndroidTrackerProvider` and make that `Application` its app-level
`android:name`:

```kotlin
class FleetApplication : Application(), AndroidTrackerProvider {
    override fun createTrackingTransport(context: Context): TrackingTransport =
        RpcTrackingTransport(hostRpc) // Adapter over the host's existing connection.
}
```

`hostRpc` implements `TrackingRpcTransport`; see [CoopNav](coopnav.md).
`createTrackerLogger`, `createMapMatcher`, and
`trackerConfig` can be overridden as needed; the default logger writes diagnostics to Android
Logcat. The service will report an initialization error and stop if the host has not installed a
provider.

Start only from a visible, resumed host `Activity`, after obtaining permissions and receiving
confirmation/authentication for the shift. Do not start it from a receiver, boot callback,
worker, application startup, or background task. Android requires staged runtime permission
requests; in particular, request foreground location first, then request background location
separately where required by the OS. Activity recognition and notification permissions should
also be requested in context; activity recognition is optional and its denial disables transitions
without blocking location tracking. `AndroidTracker.permissionsForHost()` returns the runtime
permission names relevant to the current OS version; it is a checklist, not a recommendation to
request background and foreground permissions in one dialog.

```kotlin
// After the host's permission flow succeeds and its server confirms the shift:
val session = TrackingSession.fromConfirmedShift(sessionId, performerId, brandId)
AndroidTracker.startConfirmedShift(this, session)

// From an explicit stop control in the same visible host (or use the notification's Stop action):
AndroidTracker.stop(this)
```

Pass the authenticated `sessionId` and confirmed identity supplied by the host. The helper checks
the required granted permissions and that the calling Activity has window focus. On Android 10+
continuous tracking requires background location; newer Android versions additionally enforce
foreground-service start and location-type restrictions. If permission is revoked or the OS
rejects a start, handle the resulting host-level UX and ask the user to resume explicitly.

The library creates an ongoing, low-importance notification with English and Ukrainian
translations. Its manifest sets `allowBackup=false` to protect the queue and encrypted session;
backup exclusion rules are included as an additional safeguard. This is an app-wide manifest
setting, so review/retain it if the host overrides backup policy. The session file lives in
`noBackupFilesDir`.

## Observing state and stopping

The started service exposes the shared bloc state through a local binder. Bind only after the
explicit start; the helper intentionally does not use `BIND_AUTO_CREATE`, so observing state
cannot start tracking.

```kotlin
val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val state = (binder as TrackerForegroundService.TrackerBinder).state
        lifecycleScope.launch { state.collect { trackerState -> renderTrackingStatus(trackerState) } }
    }
    override fun onServiceDisconnected(name: ComponentName) = Unit
}

AndroidTracker.bind(this, connection)
// Unbind this connection when the observing Activity is destroyed.
```

The service persists a confirmed session before dispatching `Start` and returns `START_STICKY`
for tracking. Only an OS sticky restart can restore that encrypted confirmed session. Explicit
stop immediately disables callbacks and cancels collection and upload jobs. It returns
`START_NOT_STICKY`, attempts to clear the persisted session, and stops the service even if deletion
fails. Such a failure is exposed in the bound state and logged; leftover data does not start
tracking. After a stop, the host must confirm a shift before starting again. A force-stop,
uninstall, device policy, or permission loss can prevent OS restart.
An identity change stops the old runtime before clearing its session. If clearing or saving
the replacement fails, tracking stays stopped and the service reports the error.

For application-owned observation, retain the binder outside the Activity and call its
suspending `stopTracking()` on logout/token expiry, even in the background. It waits for
runtime shutdown. If no binder is available, `AndroidTracker.stopFromHost(context)` clears
the encrypted session and stops the existing service without starting a new one.
Override `AndroidTrackerProvider.allowsStickyRestore(context)` to return `false` when the host
requires fresh authentication/registration after process death. Normal background collection
continues, but an OS sticky restart will not restore that host's saved session.

While a session is active, including `STILL`, the service holds a partial CPU wake lock with a
60-second timeout renewed every 30 seconds. It releases the lock on teardown and failed startup.
This increases battery usage and does not override Android Doze or OEM power restrictions.

## Platform behavior and limits

- The runtime dispatches the shared bloc's `Tick` event once per second. While moving, location
  requests run at a 1-second interval with high accuracy, zero batching delay, and wait until the
  provider reports an accurate fix. Only one stream is active. Fused location is primary. Platform
  GPS is requested only after the fused request fails, including on devices without working Google
  Play services. Network is requested only when neither precise provider can start, and a
  `PlatformError` says that network fixes are not a precise fallback. Passive provider updates are
  not consumed.
  `LocationFix` preserves provider wall time and Android `elapsedRealtimeNanos`; late callbacks
  are not rewritten as fresh observations.
- Active motion sensing requests approximately 50 Hz, including before a confirmed stop.
  Rotation-vector attitude (or gravity and
  magnetic-field fallback) transforms acceleration into earth coordinates. Magnetic north is
  corrected with `GeomagneticField` declination, and longitudinal acceleration is projected as
  `east * sin(course) + north * cos(course)`. Both inputs come only from the bloc's accepted GPS
  or fused estimate, never a raw callback. Longitudinal acceleration reuses that course for up to
  3 seconds from the accepted estimate's monotonic timestamp; a staler course is withheld so a
  turn is not projected onto the old track. Predictions never renew it.
  No phone mounting axis is assumed. Yaw is the negative world-up
  component of Android's right-handed gyroscope vector, matching the core's clockwise-from-north
  convention. Fresh gravity-compensated acceleration magnitude and available barometer readings
  still reach the core when navigation inputs are missing. Longitudinal acceleration and yaw are
  independently `null` when their inputs are unavailable. This lets the core detect quiet motion
  even before the first moving fix. `STILL` wake samples carry magnitude with both controls `null`.
  Missing hardware is reported through `PlatformError` and the injected logger.
- In `STILL`, precise sensors and raw GNSS callbacks pause. Location continues as a 5-second
  high-accuracy watchdog so a real departure can release the stop anchor. Switching that
  interval does not remove the location callback. The first fused result is not withheld
  until a precise solution; a cached fix at most 5 seconds old may be delivered. A real
  outage still clears the estimate after 15 seconds, which hides the map pin. A low-rate accelerometer and
  a hardware significant-motion trigger are retained where available; three acceleration samples
  over 0.8 m/s² wake precise motion sensing. Wake detection subtracts the last trusted gravity
  vector and adjusts that baseline only below 0.2 m/s². Hardware support and sample cadence vary by device.
- Activity transitions (still, walking, vehicle) use a mutable `PendingIntent` with a fixed
  app-private receiver so Play services can attach results. The receiver binds without
  `BIND_AUTO_CREATE` and forwards only to the matching active runtime; it never starts or
  restores tracking. Absent Play services or permission denial disables this advisory input.
- Hardware steps preserve the sensor's elapsed-realtime timestamp, so delayed steps are not
  treated as current. An optional barometer adds fresh pressure in hPa to active motion
  samples; missing or stale pressure is omitted.
- Raw GNSS evidence is optional. AGC/CN0 values are emitted only when exposed. UTC conversion is
  provided only when the GNSS clock exposes a leap-second count and full bias, using the GPS epoch
  and GPS-minus-UTC leap offset; otherwise `utcTimeMs` remains `null` (unverified). GNSS raw
  measurements are not available on every device.
- The shared core ignores IMU samples older than one second and expires a position estimate after
  its 15-second prediction horizon; Android monotonic timestamps are preserved for those checks.
- `EncryptedTrackQueue` stores AES-GCM encrypted serialized points in SQLCipher with
  `PRAGMA synchronous=FULL`. An Android Keystore
  HMAC key creates a stable opaque partition from length-prefixed identity fields; row
  ciphertext AAD binds that partition and the point ID. A single mutex serializes append, peek,
  size and exact-prefix acknowledgement for each queue instance. Acknowledgement is transactional.
  Authentication, schema and SQLite corruption failures are surfaced without automatic
  deletion or database recreation. Keystore key loss can make existing encrypted data
  unrecoverable; a missing identity key with queued rows is reported rather than replaced
  with a key that would hide the old partitions.
  The SQLCipher database key is separately wrapped by Keystore; legacy rows migrate
  transactionally without deleting the original database. See [storage](storage.md).
- Both stores persist the IV generated by Android Keystore for each AES-GCM write.
  `EncryptedSessionStore` uses `AtomicFile`. `SecurePointIdGenerator` produces
  cryptographically random 128-bit IDs. `AndroidTrafficCounter` reports per-UID byte totals since
  that counter instance was created and throws if Android reports traffic statistics unsupported.

The Android notification is the library's only UI. Its user-visible strings are resources in
`values/strings.xml` and `values-uk/strings.xml`; the host remains responsible for its own
permission rationale, shift confirmation, retry and error UI.
