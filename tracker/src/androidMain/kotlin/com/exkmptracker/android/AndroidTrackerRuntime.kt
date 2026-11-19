package com.exkmptracker.android

import android.content.Context
import android.util.Log
import com.exkmptracker.bloc.TrackerBloc
import com.exkmptracker.bloc.TrackerEvent
import com.exkmptracker.model.Activity
import com.exkmptracker.model.TrackerConfig
import com.exkmptracker.model.TrackingSession
import com.exkmptracker.ports.MapMatcher
import com.exkmptracker.ports.TrackingTransport
import com.exkmptracker.ports.TrackerLogger
import com.exkmptracker.ports.SensorController
import com.exkmptracker.cooperative.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Host applications provide the authenticated transport. This interface is implemented by the
 * Application object; no process-global runtime or fake endpoint is used by the library.
 */
interface AndroidTrackerProvider {
    fun allowsStickyRestore(context: Context): Boolean = true
    fun createTrackingTransport(context: Context): TrackingTransport
    fun createTrackerLogger(context: Context): TrackerLogger = AndroidTrackerLogger()
    fun createMapMatcher(context: Context): MapMatcher? = null
    fun trackerConfig(context: Context): TrackerConfig = TrackerConfig()
    fun createEdgeVisionController(context: Context): EdgeVisionController? = null
    fun vehicleClass(context: Context): VehicleClass = VehicleClass.CAR
}

class AndroidTrackerLogger : TrackerLogger {
    override fun error(message: String, cause: Throwable?) {
        Log.e(LOG_TAG, message, cause)
    }

    private companion object { const val LOG_TAG = "ExKMPTracker" }
}

/** One runtime is created per foreground-service lifetime. */
class AndroidTrackerRuntime internal constructor(
    context: Context,
    private val provider: AndroidTrackerProvider,
) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val logger = provider.createTrackerLogger(appContext)
    private val sessionStore = EncryptedSessionStore(appContext)
    private val queue = EncryptedTrackQueue(appContext)
    private val pendingPlatformErrors = mutableListOf<String>()
    private lateinit var blocInstance: TrackerBloc
    private var closed = false
    private var queueClosed = false
    private var startedIdentity: com.exkmptracker.model.TrackingIdentity? = null
    private var tickerJob: Job? = null

    @Synchronized
    internal fun report(message: String, cause: Throwable?) {
        logger.error(message, cause)
        if (closed) return
        if (::blocInstance.isInitialized) {
            blocInstance.dispatch(TrackerEvent.PlatformError(message))
        } else {
            pendingPlatformErrors += message
        }
    }

    @Synchronized
    private fun dispatchIfActive(event: TrackerEvent) {
        if (!closed && ::blocInstance.isInitialized) blocInstance.dispatch(event)
    }

    val sensors = AndroidSensorController(appContext, ::dispatchIfActive, ::report)
    val location = AndroidLocationSource(appContext, ::dispatchIfActive, ::report)
    val gnss = AndroidGnssMonitor(appContext, ::dispatchIfActive, ::report)
    val activityRecognition = AndroidActivityRecognition(appContext, ::report)

    init {
        try {
            val transport = provider.createTrackingTransport(appContext)
            blocInstance = TrackerBloc(
                scope = scope,
                clock = AndroidTrackerClock(),
                queue = queue,
                transport = transport,
                idGenerator = SecurePointIdGenerator(),
                logger = logger,
                sensors = object : SensorController {
                    override fun setStationary(stationary: Boolean) =
                        this@AndroidTrackerRuntime.setStationary(stationary)
                },
                config = provider.trackerConfig(appContext),
                mapMatcher = provider.createMapMatcher(appContext),
                cooperativeTransport = transport as? CooperativeTransport,
                vision = provider.createEdgeVisionController(appContext),
                vehicleClass = provider.vehicleClass(appContext),
            )
            synchronized(this) {
                pendingPlatformErrors.forEach { blocInstance.dispatch(TrackerEvent.PlatformError(it)) }
                pendingPlatformErrors.clear()
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    val bloc: TrackerBloc get() = blocInstance
    val sessions get() = sessionStore

    suspend fun section(number: Int, enabled: Boolean) = blocInstance.section(number, enabled)

    suspend fun start(session: TrackingSession) {
        check(!closed) { "Tracker runtime is closed" }
        check(startedIdentity == null || startedIdentity == session.identity) {
            "A different tracking identity requires a fresh Android runtime"
        }
        queue.size(session.identity)
        sessionStore.save(session)
        check(!closed) { "Tracker runtime closed during session save" }
        blocInstance.dispatch(TrackerEvent.Start(session))
        if (startedIdentity == null) {
            startedIdentity = session.identity
            setStationary(false)
            activityRecognition.start()
            startTicker()
        }
    }

    suspend fun restore(): TrackingSession? {
        check(!closed) { "Tracker runtime is closed" }
        val session = sessionStore.load() ?: return null
        check(!closed) { "Tracker runtime closed during session restore" }
        queue.size(session.identity)
        blocInstance.dispatch(TrackerEvent.Start(session))
        startedIdentity = session.identity
        setStationary(false)
        activityRecognition.start()
        startTicker()
        return session
    }

    fun activityChanged(activity: Activity) {
        if (!closed) blocInstance.dispatch(TrackerEvent.ActivityChanged(activity))
    }

    fun setStationary(stationary: Boolean) {
        if (closed) return
        location.setStationary(stationary)
        gnss.setStationary(stationary)
        sensors.setStationary(stationary)
    }

    suspend fun stop(clearSession: Boolean) {
        quiesce()
        try {
            if (clearSession) sessionStore.clear()
        } finally {
            shutdown()
        }
    }

    /** Stop collection and cancel upload/actor jobs before any potentially blocking storage work. */
    internal fun quiesce() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        scope.cancel()
        tickerJob = null
        closePlatformComponents()
    }

    suspend fun shutdown() {
        quiesce()
        if (::blocInstance.isInitialized) blocInstance.shutdown()
        scope.coroutineContext[Job]?.join()
        if (!queueClosed) {
            queueClosed = true
            queue.close()
        }
    }

    override fun close() {
        quiesce()
        CoroutineScope(Dispatchers.Main.immediate).launch { shutdown() }
    }

    private fun closePlatformComponents() {
        listOf<() -> Unit>(
            activityRecognition::close,
            location::close,
            gnss::close,
            sensors::close,
        ).forEach { cleanup ->
            try {
                cleanup()
            } catch (error: Exception) {
                logger.error("Android tracker component cleanup failed", error)
            }
        }
    }

    private fun startTicker() {
        if (tickerJob != null || closed) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                if (!closed) blocInstance.dispatch(TrackerEvent.Tick)
            }
        }
    }

    private companion object { const val TICK_INTERVAL_MS = 1_000L }
}
