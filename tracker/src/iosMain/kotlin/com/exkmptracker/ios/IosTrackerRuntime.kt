package com.exkmptracker.ios

import com.exkmptracker.bloc.TrackerBloc
import com.exkmptracker.bloc.TrackerEvent
import com.exkmptracker.bloc.TrackerState
import com.exkmptracker.model.TrackerConfig
import com.exkmptracker.model.TrackingSession
import com.exkmptracker.ports.MapMatcher
import com.exkmptracker.ports.PointIdGenerator
import com.exkmptracker.ports.SessionStore
import com.exkmptracker.ports.TrackQueue
import com.exkmptracker.ports.TrackerClock
import com.exkmptracker.ports.TrackerLogger
import com.exkmptracker.ports.TrackingTransport
import com.exkmptracker.cooperative.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.scope.Scope as KoinScope

/**
 * iOS-owned lifecycle around the shared tracker. There is deliberately no singleton:
 * the host supplies a scope and a transport and explicitly starts a confirmed session.
 *
 * Start, restore, stop and shutdown are serialized by an instance mutex. That lifecycle lock may
 * be followed by the [IosProtectedStorage] lock inside queue/session operations, never the
 * reverse, and neither lock is re-entered.
 */
class IosTrackerRuntime(
    private val scope: CoroutineScope,
    transport: TrackingTransport,
    logger: TrackerLogger = IosTrackerLogger(),
    private val config: TrackerConfig = TrackerConfig(),
    mapMatcher: MapMatcher? = null,
    storage: IosProtectedStorage = iosStorageFromGraphOrNew(),
    private val queue: TrackQueue = iosQueueFor(storage),
    sessionStore: SessionStore = iosSessionFor(storage),
    clock: TrackerClock = IosTrackerClock(),
    idGenerator: PointIdGenerator = IosPointIdGenerator(),
    vision: EdgeVisionController? = null,
    vehicleClass: VehicleClass = VehicleClass.CAR,
) {
    private val trackerLogger = logger
    private val mainDispatcher: CoroutineDispatcher = iosMainDispatcher()
    private val sessions = sessionStore
    private val clockSource = clock
    private val lifecycle = Mutex()
    private val sensorController = DeferredSensorController()
    private val bloc = TrackerBloc(
        scope = scope,
        clock = clock,
        queue = queue,
        transport = transport,
        idGenerator = idGenerator,
        logger = logger,
        sensors = sensorController,
        config = config,
        mapMatcher = mapMatcher,
        cooperativeTransport = transport as? CooperativeTransport,
        vision = vision,
        vehicleClass = vehicleClass,
    )
    private val courseReference = IosAcceptedCourseReference(clock)
    private var locationSource: IosLocationSource? = null
    private var timerJob: Job? = null
    private var courseObserverJob: Job? = null
    private var activeSession: TrackingSession? = null
    private var activeBackgroundConfiguration = false
    private var closed = false
    private var acceptingLocationEvents = false
    private var ownsScope = false
    private var boundKoinScope: KoinScope? = null

    /**
     * Exact one-argument Swift initializer (`IosTrackerRuntime(transport:)`). It creates an
     * instance-owned scope and default protected storage shared by the queue and session store.
     */
    constructor(transport: TrackingTransport) : this(
        transport = transport,
        config = TrackerConfig(),
        logger = IosTrackerLogger(),
        storage = iosStorageFromGraphOrNew(),
    )

    constructor(transport: TrackingTransport, vision: EdgeVisionController, vehicleClass: VehicleClass) : this(
        scope = CoroutineScope(SupervisorJob() + iosComputeDispatcher()),
        transport = transport,
        vision = vision,
        vehicleClass = vehicleClass,
    ) {
        ownsScope = true
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    suspend fun section(number: Int, enabled: Boolean) = bloc.section(number, enabled)

    /**
     * Explicit Swift initializer with an instance-owned scope. A new [IosProtectedStorage] whose
     * directory standardizes to the Koin queue directory reuses that queue and session store.
     * A different directory keeps its own. The scope uses the Koin compute dispatcher when the
     * graph has one.
     */
    constructor(
        transport: TrackingTransport,
        config: TrackerConfig,
        logger: TrackerLogger,
        storage: IosProtectedStorage,
    ) : this(
        scope = CoroutineScope(SupervisorJob() + iosComputeDispatcher()),
        transport = transport,
        logger = logger,
        config = config,
        mapMatcher = null,
        storage = storage,
        queue = iosQueueFor(storage),
        sessionStore = iosSessionFor(storage),
        clock = IosTrackerClock(),
        idGenerator = IosPointIdGenerator(),
    ) {
        ownsScope = true
    }

    /** Graph-built runtime. `internal` keeps this initializer out of the Swift header. */
    internal constructor(
        scope: CoroutineScope,
        transport: TrackingTransport,
        logger: TrackerLogger,
        config: TrackerConfig,
        mapMatcher: MapMatcher?,
        storage: IosProtectedStorage,
        queue: TrackQueue,
        sessionStore: SessionStore,
        clock: TrackerClock,
        idGenerator: PointIdGenerator,
        vision: EdgeVisionController?,
        vehicleClass: VehicleClass,
        koinScope: KoinScope,
    ) : this(
        scope = scope,
        transport = transport,
        logger = logger,
        config = config,
        mapMatcher = mapMatcher,
        storage = storage,
        queue = queue,
        sessionStore = sessionStore,
        clock = clock,
        idGenerator = idGenerator,
        vision = vision,
        vehicleClass = vehicleClass,
    ) {
        ownsScope = true
        boundKoinScope = koinScope
    }

    init {
        sensorController.attach(
            IosSensorController(
                dispatch = bloc::dispatch,
                logger = logger,
                clock = clock,
                trackBearingDegrees = courseReference::trustedBearingDegrees,
                magneticDeclinationDegrees = { locationSource?.magneticDeclinationDegrees },
                compassHeading = { locationSource?.latestHeading },
            ),
        )
        // Accepted BLoC estimates are the only source of the IMU course frame. The job is retained
        // and cancelled by shutdown so an injected long-lived host scope does not retain this runtime.
        courseObserverJob = scope.launch { bloc.state.collect(courseReference::onState) }
    }

    /** The BLoC's state is exposed directly; this runtime keeps no second state copy. */
    val state: StateFlow<TrackerState>
        get() = bloc.state

    /** Device features, not a claim that every sensor is available or authorized. */
    val sensorCapabilities: Set<String>
        get() = sensorController.capabilities

    val platformLimitations: Set<String> = setOf(
        "raw-gnss-clock-unavailable",
        "raw-gnss-agc-unavailable",
        "app-uid-traffic-counter-unsupported",
        "simulated-location-detection-requires-ios-15",
    )

    /**
     * Start only after the host has confirmed a session and obtained location authorization.
     * `backgroundLocationConfigured` is a host attestation that UIBackgroundModes includes
     * `location`; background updates are still enabled only with Always authorization.
     *
     * While running, the same driver/brand identity updates the saved session and the BLoC session
     * without restarting sensors. A different identity fully stops the old session (including
     * clearing its saved session) before the new one starts; if that stop fails, the error is
     * thrown and the new session is not started.
     */
    @Throws(Exception::class)
    suspend fun startConfirmedSession(
        session: TrackingSession,
        backgroundLocationConfigured: Boolean,
    ) {
        lifecycle.withLock { startLocked(session, backgroundLocationConfigured) }
    }

    /** Foreground-only start; equivalent to `backgroundLocationConfigured = false`. */
    @Throws(Exception::class)
    suspend fun startConfirmedSession(session: TrackingSession) {
        startConfirmedSession(session, backgroundLocationConfigured = false)
    }

    /**
     * Restoration is opt-in. The host must decide when a previously saved confirmed session
     * should resume; constructing this runtime never starts it automatically, and a session
     * explicitly stopped by the user is never returned.
     */
    @Throws(Exception::class)
    suspend fun restoreSavedSession(backgroundLocationConfigured: Boolean): TrackingSession? =
        lifecycle.withLock {
            val saved = sessions.load() ?: return@withLock null
            startLocked(saved, backgroundLocationConfigured)
            saved
        }

    @Throws(Exception::class)
    suspend fun stop() {
        lifecycle.withLock { stopLocked(clearSavedSession = true) }
    }

    private suspend fun startLocked(session: TrackingSession, backgroundLocationConfigured: Boolean) {
        check(!closed) { "This iOS tracker runtime has been shut down" }
        val current = activeSession
        if (current != null) {
            if (current.identity == session.identity) {
                updateRunningSession(session, backgroundLocationConfigured)
                return
            }
            stopLocked(clearSavedSession = true)
        }

        queue.size(session.identity)
        val source = withContext(mainDispatcher) {
            val existing = locationSource ?: IosLocationSource(
                clock = clockSource,
                config = config,
                dispatch = ::dispatchLocationEvent,
                logger = trackerLogger,
            ).also { locationSource = it }
            if (existing.start(backgroundLocationConfigured)) existing else null
        } ?: return

        try {
            sessions.save(session)
        } catch (error: Throwable) {
            withContext(mainDispatcher + NonCancellable) {
                source.stop()
                locationSource = null
            }
            trackerLogger.error("Could not persist confirmed tracker session", error)
            throw error
        }

        bloc.dispatch(TrackerEvent.Start(session))
        activeSession = session
        activeBackgroundConfiguration = backgroundLocationConfigured
        withContext(mainDispatcher) {
            acceptingLocationEvents = true
            sensorController.startTrackingOnMain()
        }
        reportPlatformLimitations()
        timerJob = scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MILLIS)
                bloc.dispatch(TrackerEvent.Tick)
            }
        }
    }

    private suspend fun updateRunningSession(session: TrackingSession, backgroundLocationConfigured: Boolean) {
        if (session != activeSession) {
            sessions.save(session)
            bloc.dispatch(TrackerEvent.Start(session))
            activeSession = session
        }
        if (backgroundLocationConfigured != activeBackgroundConfiguration) {
            // Re-applies authorization/background configuration on the running manager; updates
            // are not stopped, so no sensor restart or data gap is introduced.
            withContext(mainDispatcher) { locationSource?.start(backgroundLocationConfigured) }
            activeBackgroundConfiguration = backgroundLocationConfigured
        }
    }

    /**
     * Teardown is unconditional: every native callback, the tick timer and the BLoC session stop
     * even when an individual step (including persistence clearing) fails. The first failure is
     * logged, surfaced in BLoC diagnostics and rethrown to Swift after teardown completes.
     */
    private suspend fun stopLocked(clearSavedSession: Boolean) {
        var failure: Throwable? = null
        fun record(error: Throwable) {
            val first = failure
            if (first == null) failure = error else first.addSuppressed(error)
        }
        activeSession = null
        activeBackgroundConfiguration = false
        try {
            timerJob?.cancelAndJoin()
        } catch (error: Throwable) {
            record(error)
        } finally {
            timerJob?.cancel()
            timerJob = null
        }
        try {
            withContext(mainDispatcher + NonCancellable) {
                acceptingLocationEvents = false
                try {
                    locationSource?.stop()
                } finally {
                    locationSource = null
                    sensorController.stopTrackingOnMain()
                }
            }
        } catch (error: Throwable) {
            record(error)
        }
        courseReference.clear()
        try {
            bloc.dispatch(TrackerEvent.Stop)
        } catch (error: Throwable) {
            record(error)
        }
        if (clearSavedSession) {
            try {
                withContext(NonCancellable) { sessions.clear() }
            } catch (error: Throwable) {
                trackerLogger.error("Tracking stopped, but the saved session could not be cleared", error)
                try {
                    bloc.dispatch(
                        TrackerEvent.PlatformError(
                            "Tracking stopped, but the saved session could not be cleared: ${error.message}",
                        ),
                    )
                } catch (_: Throwable) {
                }
                record(error)
            }
        }
        failure?.let { throw it }
    }

    /**
     * Stop native resources, the internal course observer and the shared BLoC. An injected
     * CoroutineScope remains host-owned and is not cancelled; an instance-owned scope is.
     * Observation handles returned by [observeState] should be cancelled by their owner.
     */
    @Throws(Exception::class)
    suspend fun shutdown() {
        lifecycle.withLock {
            if (closed) return@withLock
            closed = true
            try {
                stopLocked(clearSavedSession = false)
            } finally {
                try {
                    withContext(NonCancellable) {
                        courseObserverJob?.cancelAndJoin()
                        courseObserverJob = null
                    }
                } finally {
                    try {
                        withContext(NonCancellable) { bloc.shutdown() }
                    } finally {
                        if (ownsScope) scope.cancel()
                        val bound = boundKoinScope
                        boundKoinScope = null
                        bound?.close()
                    }
                }
            }
        }
    }

    /**
     * Swift-friendly observer of the same BLoC StateFlow. Call [IosStateObservation.cancel]
     * when the observer is no longer needed (for example in `deinit`).
     */
    fun observeState(observer: (TrackerState) -> Unit): IosStateObservation =
        IosStateObservation(scope.launch { bloc.state.collect { observer(it) } })

    private fun reportPlatformLimitations() {
        val message =
            "iOS does not expose raw GNSS receiver clock/AGC evidence or per-app UID traffic totals; no evidence or zero totals are fabricated"
        trackerLogger.error(message, null)
        bloc.dispatch(TrackerEvent.PlatformError(message))
    }

    private fun dispatchLocationEvent(event: TrackerEvent) {
        if (event is TrackerEvent.Position && !acceptingLocationEvents) return
        bloc.dispatch(event)
    }

    private companion object {
        const val TICK_INTERVAL_MILLIS = 1_000L
    }
}

/** Cancellation handle for [IosTrackerRuntime.observeState] with a parameterless Swift `cancel()`. */
class IosStateObservation internal constructor(private val job: Job) {
    val isActive: Boolean
        get() = job.isActive

    fun cancel() {
        job.cancel()
    }
}

/** iOS does not provide app-UID total traffic counters; callers must handle this explicitly. */
class IosTrafficCounter : com.exkmptracker.ports.TrafficCounter {
    override fun totalIncomingBytes(): Long =
        throw UnsupportedOperationException("iOS does not expose total network traffic for an app UID")

    override fun totalOutgoingBytes(): Long =
        throw UnsupportedOperationException("iOS does not expose total network traffic for an app UID")
}

private class DeferredSensorController : com.exkmptracker.ports.SensorController {
    private lateinit var delegate: IosSensorController

    fun attach(controller: IosSensorController) {
        delegate = controller
    }

    override fun setStationary(stationary: Boolean) {
        if (::delegate.isInitialized) delegate.setStationary(stationary)
    }

    val capabilities: Set<String>
        get() = if (::delegate.isInitialized) delegate.capabilities else emptySet()

    fun startTrackingOnMain() {
        delegate.startTrackingOnMain()
    }

    fun stopTrackingOnMain() {
        if (::delegate.isInitialized) delegate.stopTrackingOnMain()
    }
}
