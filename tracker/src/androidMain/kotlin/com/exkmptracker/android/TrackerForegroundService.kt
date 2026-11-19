package com.exkmptracker.android

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.os.Binder
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.net.Uri
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.exkmptracker.R
import com.exkmptracker.model.Activity as TrackerActivity
import com.exkmptracker.model.TrackingSession
import com.exkmptracker.bloc.TrackerState
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The host must explicitly start this service from a visible Activity after confirming a shift
 * and obtaining the permissions listed by [AndroidTracker.permissionsForHost].
 */
class TrackerForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleMutex = Mutex()
    private var runtime: AndroidTrackerRuntime? = null
    private var stateJob: Job? = null
    private var currentSession: TrackingSession? = null
    private var stopping = false
    private var allowStickyRestore = true
    private val wakeLock by lazy { TrackingWakeLock(this, ::report) }
    private var lastTransitionNanos = 0L
    private var recognizedActivity = TrackerActivity.UNKNOWN
    private val binder = TrackerBinder()
    private val _trackingState = MutableStateFlow(TrackerState())
    val trackingState: StateFlow<TrackerState> = _trackingState.asStateFlow()

    inner class TrackerBinder : Binder() {
        /** Bind only after an explicit start; no auto-created service or implicit tracking start. */
        val state: StateFlow<TrackerState> get() = trackingState

        suspend fun section(number: Int, enabled: Boolean) {
            val active = checkNotNull(runtime) { "Start the tracking service before changing sections" }
            check(!stopping) { "Tracking service is stopping" }
            active.section(number, enabled)
        }

        /** Logout/token expiry must stop tracking even when the host Activity is backgrounded. */
        suspend fun stopTracking() = withContext(NonCancellable) {
            lifecycleMutex.withLock {
                stopping = true
                allowStickyRestore = false
                stateJob?.cancel()
                stateJob = null
                try {
                    runtime?.quiesce()
                } finally {
                    wakeLock.stop()
                    stopExplicitly()
                }
            }
        }

        internal fun deliverTransitions(token: Uri, events: List<ActivityTransitionEvent>) {
            val active = runtime ?: return
            if (stopping || currentSession == null || token != active.activityRecognition.deliveryToken) return
            val now = SystemClock.elapsedRealtimeNanos()
            for (event in events.sortedBy { it.elapsedRealTimeNanos }) {
                val time = event.elapsedRealTimeNanos
                if (time <= lastTransitionNanos || time > now) continue
                val activity = when (event.activityType) {
                    DetectedActivity.STILL -> TrackerActivity.STILL
                    DetectedActivity.WALKING -> TrackerActivity.WALKING
                    DetectedActivity.IN_VEHICLE -> TrackerActivity.IN_VEHICLE
                    else -> continue
                }
                val next = when (event.transitionType) {
                    ActivityTransition.ACTIVITY_TRANSITION_ENTER -> activity
                    ActivityTransition.ACTIVITY_TRANSITION_EXIT ->
                        if (recognizedActivity == activity) TrackerActivity.UNKNOWN else continue
                    else -> continue
                }
                lastTransitionNanos = time
                recognizedActivity = next
                active.activityChanged(next)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (!stopping) {
                stopping = true
                allowStickyRestore = false
                stateJob?.cancel()
                stateJob = null
                _trackingState.value = TrackerState()
                try {
                    runtime?.quiesce()
                } catch (error: Exception) {
                    android.util.Log.e(LOG_TAG, "Unable to quiesce all tracker resources", error)
                } finally {
                    try {
                        wakeLock.stop()
                    } finally {
                        serviceScope.launch {
                            withContext(NonCancellable) {
                                lifecycleMutex.withLock { stopExplicitly() }
                            }
                        }
                    }
                }
            }
            return START_NOT_STICKY
        }
        if (stopping || (intent == null && !allowStickyRestore)) return START_NOT_STICKY
        if (intent == null && (application as? AndroidTrackerProvider)?.allowsStickyRestore(this) != true) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent != null && intent.action != ACTION_START) return START_NOT_STICKY
        try {
            promoteToForeground()
        } catch (error: Exception) {
            report("Unable to promote tracking service to location foreground service", error)
            wakeLock.stop()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                val encoded = intent.getStringExtra(EXTRA_SESSION)
                if (encoded == null) {
                    report("Explicit tracking start did not include a confirmed session")
                    if (currentSession == null) {
                        wakeLock.stop()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    }
                } else {
                    serviceScope.launch { lifecycleMutex.withLock {
                        if (stopping) return@withLock
                        try {
                            promoteToForeground()
                            val session = Json.decodeFromString<TrackingSession>(encoded)
                            installSession(session)
                        } catch (error: Exception) {
                            if (stopping) return@withLock
                            report("Unable to start tracking session", error)
                            if (runtime == null || currentSession == null) {
                                allowStickyRestore = false
                                _trackingState.value = TrackerState(error = "Unable to start tracking session")
                                wakeLock.stop()
                                runtime?.close()
                                runtime = null
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf(startId)
                            }
                        }
                    } }
                }
            }
            null -> if (intent == null) {
                serviceScope.launch { lifecycleMutex.withLock {
                    if (!stopping && allowStickyRestore) restoreRuntime()
                } }
            }
        }
        return START_STICKY
    }

    private suspend fun installSession(session: TrackingSession) {
        val previous = runtime
        if (previous != null && currentSession?.identity == session.identity) {
            previous.start(session)
            currentSession = session
            return
        }
        if (previous != null) {
            allowStickyRestore = false
            runtime = null
            currentSession = null
            stateJob?.cancel()
            stateJob = null
            _trackingState.value = TrackerState()
            try {
                try {
                    previous.quiesce()
                } finally {
                    wakeLock.stop()
                }
                previous.sessions.clear()
            } finally {
                withContext(NonCancellable) { previous.shutdown() }
            }
        }
        if (stopping) return
        val fresh = createRuntime() ?: return
        runtime = fresh
        fresh.start(session)
        currentSession = session
        allowStickyRestore = true
        lastTransitionNanos = 0L
        recognizedActivity = TrackerActivity.UNKNOWN
        wakeLock.start()
        observeState(fresh)
    }

    private suspend fun restoreRuntime(): AndroidTrackerRuntime? {
        if (runtime != null) return runtime
        val fresh = createRuntime() ?: return null
        runtime = fresh
        try {
            val restored = fresh.restore()
            if (restored == null) {
                fresh.close()
                runtime = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return null
            }
            currentSession = restored
            wakeLock.start()
            observeState(fresh)
            return fresh
        } catch (error: Exception) {
            if (stopping) return null
            report("Unable to restore the encrypted tracking session", error)
            wakeLock.stop()
            fresh.close()
            runtime = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return null
        }
    }

    private fun createRuntime(): AndroidTrackerRuntime? {
        val provider = application as? AndroidTrackerProvider
        if (provider == null) {
            report("Host Application must implement AndroidTrackerProvider")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return null
        }
        return try {
            AndroidTrackerRuntime(this, provider)
        } catch (error: Exception) {
            report("Unable to initialize the Android tracker runtime", error)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            null
        }
    }

    private fun observeState(activeRuntime: AndroidTrackerRuntime) {
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            activeRuntime.bloc.state.collect { state: TrackerState ->
                activeRuntime.sensors.updateAcceptedEstimate(
                    if (state.sections.stage1) state.autonomousEstimate else null)
                _trackingState.value = state
                activeRuntime.setStationary(state.activity == TrackerActivity.STILL)
            }
        }
    }

    private suspend fun stopExplicitly() {
        val active = runtime
        var failure: String? = null
        try {
            (active?.sessions ?: EncryptedSessionStore(this)).clear()
        } catch (error: Exception) {
            failure = "Tracking stopped but the encrypted session could not be cleared"
            _trackingState.value = TrackerState(error = failure)
            report(failure, error)
        } finally {
            runtime = null
            currentSession = null
            _trackingState.value = TrackerState(error = failure)
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } finally {
                stopSelf()
                try {
                    active?.shutdown()
                } finally {
                    stopping = false
                }
            }
        }
    }

    override fun onDestroy() {
        wakeLock.stop()
        stateJob?.cancel()
        runtime?.close()
        runtime = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun promoteToForeground() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.tracker_notification_title))
            .setContentText(getString(R.string.tracker_notification_text))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.tracker_notification_stop),
                PendingIntent.getService(this, 0, stopIntent(this), PendingIntent.FLAG_IMMUTABLE),
            )
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            getString(R.string.tracker_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.tracker_notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun report(message: String, cause: Throwable? = null) {
        android.util.Log.e(LOG_TAG, message, cause)
        runtime?.report(message, cause)
    }

    companion object {
        private const val LOG_TAG = "ExKMPTracker"
        private const val NOTIFICATION_CHANNEL = "exkmp_tracker_location"
        private const val NOTIFICATION_ID = 4318
        private const val ACTION_START = "com.exkmptracker.action.START_CONFIRMED"
        private const val ACTION_STOP = "com.exkmptracker.action.STOP"
        private const val EXTRA_SESSION = "com.exkmptracker.extra.SESSION"

        internal fun startIntent(context: Context, session: TrackingSession): Intent =
            Intent(context, TrackerForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION, Json.encodeToString(session))

        internal fun stopIntent(context: Context): Intent =
            Intent(context, TrackerForegroundService::class.java).setAction(ACTION_STOP)

    }
}

/** Explicit Activity-only entry points prevent background/boot-based starts. */
object AndroidTracker {
    fun permissionsForHost(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun startConfirmedShift(activity: Activity, session: TrackingSession) {
        check(isVisibleHost(activity)) { "Tracking can only be started from a visible Activity" }
        check(hasLocationPermission(activity)) { "Fine or coarse location permission is required" }
        if (Build.VERSION.SDK_INT >= 29) {
            check(hasPermission(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                "Background location permission is required for continuous shift tracking"
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            check(hasPermission(activity, Manifest.permission.POST_NOTIFICATIONS)) {
                "Notification permission is required for visible tracking status"
            }
        }
        ContextCompat.startForegroundService(activity, TrackerForegroundService.startIntent(activity, session))
    }

    fun stop(activity: Activity) {
        check(isVisibleHost(activity)) { "Tracking can only be stopped from a visible Activity" }
        activity.startService(TrackerForegroundService.stopIntent(activity))
    }

    suspend fun stopFromHost(context: Context) {
        try {
            EncryptedSessionStore(context).clear()
        } finally {
            context.stopService(Intent(context, TrackerForegroundService::class.java))
        }
    }

    /**
     * Bind only to an already-running service to observe its shared state. BIND_AUTO_CREATE is
     * intentionally omitted so observing state can never start the service or tracking.
     */
    fun bind(activity: Activity, connection: android.content.ServiceConnection): Boolean {
        check(isVisibleHost(activity)) { "Bind to tracking only from a visible Activity" }
        return activity.bindService(
            Intent(activity, TrackerForegroundService::class.java),
            connection,
            0,
        )
    }

    private fun isVisibleHost(activity: Activity) =
        !activity.isFinishing && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed) && activity.hasWindowFocus()

    private fun hasLocationPermission(context: Context) =
        hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasPermission(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
