package com.exkmptracker.bloc

import com.exkmptracker.filter.*
import com.exkmptracker.model.*
import com.exkmptracker.ports.*
import com.exkmptracker.cooperative.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.math.*

class TrackerBloc(
    scope: CoroutineScope,
    private val clock: TrackerClock,
    private val queue: TrackQueueStorage,
    private val transport: TrackingTransport,
    private val idGenerator: PointIdGenerator,
    private val logger: TrackerLogger,
    private val sensors: PlatformSensorProvider,
    private val config: TrackerConfig = TrackerConfig(),
    private val mapMatcher: MapMatcher? = null,
    private val cooperativeTransport: CooperativeTransport? = null,
    private val vision: EdgeVisionController? = null,
    private val vehicleClass: VehicleClass = VehicleClass.CAR,
) {
    private sealed interface Message {
        data class Event(val event: TrackerEvent) : Message
        data class Section(val number: Int, val enabled: Boolean, val completion: CompletableDeferred<Unit>) : Message
        data class Observations(val token: Long, val value: ObservedPeers) : Message
        data class VisionError(val token: Long, val reason: String) : Message
        data class CooperativeTick(val token: Long) : Message
        data class CooperativeResult(
            val token: Long, val snapshot: TrackingFilter, val observations: ObservedPeers,
            val timestamp: Long, val donors: List<MatchedDonor>, val error: Exception?,
        ) : Message
        data class Flushed(
            val generation: Long,
            val session: TrackingSession,
            val batch: List<TrackPoint>,
            val acceptedIds: List<String>,
            val reference: TimeReference?,
            val error: Exception?,
        ) : Message
    }

    private val messages = Channel<Message>(Channel.UNLIMITED)
    private val lifecycle = SupervisorJob(scope.coroutineContext[Job])
    private val blocScope = CoroutineScope(scope.coroutineContext + lifecycle)
    private val mutableState = MutableStateFlow(TrackerState())
    val state: StateFlow<TrackerState> = mutableState.asStateFlow()
    private var session: TrackingSession? = null
    private var generation = 0L
    private var filter = TrackingFilter(config)
    private var throttle = TrackThrottle(config)
    private var guard = TimeGuard()
    private var stop = StopAnchor()
    private var park = ParkedMotion()
    /** Published coordinate at the start of a parked blob. Noise must not move it. */
    private var parkHold: TrackEstimate? = null
    private var lastRaw: LocationFix? = null
    private var lastRecorded: TrackEstimate? = null
    private var lastPreciseTime = Double.NEGATIVE_INFINITY
    private var lastFlush = Double.NEGATIVE_INFINITY
    private var flushJob: Job? = null
    private var motion: MotionSample? = null
    private var quietSamples = 0
    private var wakeSamples = 0
    private var gyroBias = 0.0
    private var pressureRate = 0.0
    private var previousPressure: Pair<Double, Double>? = null
    private var movingSince: Double? = null
    private var compassOffset = 0.0
    private var pendingStill = false
    private var sawPreciseMotion = false
    private var consecutiveSlowPreciseFixes = 0
    private var cooperativeGeneration = 0L
    private var cooperativeTimer: Job? = null
    private var cooperativeJob: Job? = null
    private var observations: ObservedPeers? = null
    private var lastObservationTime = Double.NEGATIVE_INFINITY
    private var cooperativeEstimate: TrackEstimate? = null
    private var cooperativeFilter: TrackingFilter? = null
    private var visionFailure: String? = null
    private var lastCooperativeCapture = Double.NEGATIVE_INFINITY
    private val actor = blocScope.launch {
        try {
            for (message in messages) {
                try {
                    when (message) {
                        is Message.Event -> handle(message.event)
                        is Message.Flushed -> completed(message)
                        is Message.Section -> {
                            try {
                                changeSection(message.number, message.enabled)
                                message.completion.complete(Unit)
                            } catch (error: Exception) {
                                if (error is CancellationException) {
                                    message.completion.cancel(error)
                                    throw error
                                }
                                val failure = when (error) {
                                    is IllegalArgumentException, is IllegalStateException -> error
                                    else -> IllegalStateException("Section transition failed", error)
                                }
                                message.completion.completeExceptionally(failure)
                                fail("Section transition failed: ${error.message}", error)
                            }
                        }
                        is Message.Observations -> if (message.token == cooperativeGeneration) observe(message.value)
                        is Message.VisionError -> if (message.token == cooperativeGeneration) {
                            visionFailure = message.reason
                            observations = null
                            lastCooperativeCapture = clock.monotonicSeconds()
                            cooperativeJob?.cancel()
                            cooperativeJob = null
                            mutableState.value = state.value.copy(cooperativeStatus = message.reason,
                                estimate = state.value.autonomousEstimate)
                            cooperativeEstimate = null
                            cooperativeFilter = null
                            logger.error("Edge vision: ${message.reason}", null)
                        }
                        is Message.CooperativeTick -> if (message.token == cooperativeGeneration) syncPeers()
                        is Message.CooperativeResult -> cooperativeCompleted(message)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    fail("Tracker event failed", error)
                }
            }
        } finally {
            stopCooperative()
            mutableState.value = state.value.copy(running = false)
        }
    }

    @Throws(IllegalStateException::class)
    fun dispatch(event: TrackerEvent) {
        check(actor.isActive && messages.trySend(Message.Event(event)).isSuccess) { "TrackerBloc is closed" }
    }

    suspend fun shutdown() {
        messages.close()
        lifecycle.cancelAndJoin()
    }

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    suspend fun section(number: Int, enabled: Boolean) {
        require(number in 1..3) { "Section must be 1, 2 or 3" }
        require(number != 3 || !enabled) { "Section 3 (MoQ) is not implemented" }
        val completion = CompletableDeferred<Unit>()
        check(actor.isActive) { "TrackerBloc is closed" }
        messages.send(Message.Section(number, enabled, completion))
        selectCompletion(completion)
    }

    private suspend fun selectCompletion(completion: CompletableDeferred<Unit>) =
        kotlinx.coroutines.selects.select<Unit> {
            completion.onAwait { }
            actor.onJoin {
                check(completion.isCompleted) { "TrackerBloc closed during section transition" }
                completion.await()
            }
        }

    private fun changeSection(number: Int, enabled: Boolean) {
        val old = state.value.sections
        if (number == 2 && enabled) {
            check(old.stage1) { "Section 2 requires section 1" }
            check(cooperativeTransport != null && vision != null) {
                "Section 2 requires an injected cooperative transport, licensed model and calibrated vision controller"
            }
        }
        val next = when (number) {
            1 -> old.copy(stage1 = enabled, stage2 = old.stage2 && enabled, stage3 = false)
            2 -> old.copy(stage2 = enabled)
            else -> old.copy(stage3 = false)
        }
        if (old == next) return
        if (!next.stage2 || old.stage2 != next.stage2) stopCooperative()
        if (old.stage1 != next.stage1) {
            generation++
            flushJob?.cancel()
            flushJob = null
            lastFlush = Double.NEGATIVE_INFINITY
            filter = TrackingFilter(config)
            stop = StopAnchor()
            clearPark()
            throttle = TrackThrottle(config)
            pendingStill = false
            quietSamples = 0
            movingSince = null
            resetMotionEvidence()
            sensors.setStationary(false)
        }
        mutableState.value = state.value.copy(sections = next,
            estimate = when {
                !next.stage1 -> lastRaw?.let(::rawEstimate)
                old.stage1 != next.stage1 -> null
                else -> state.value.autonomousEstimate
            },
            autonomousEstimate = if (old.stage1 != next.stage1) null else state.value.autonomousEstimate,
            cooperativeStatus = null,
            activity = if (!next.stage1) Activity.UNKNOWN else state.value.activity)
        if (next.stage2 && session != null) startCooperative()
    }

    private fun rawEstimate(fix: LocationFix) = TrackEstimate(fix.latitude, fix.longitude, fix.accuracyMeters,
        fix.speedMps, fix.bearingDegrees, fix.monotonicSeconds, fix.source)

    private fun startCooperative() {
        if (cooperativeTimer != null || !state.value.sections.stage2) return
        val token = ++cooperativeGeneration
        visionFailure = null
        mutableState.value = state.value.copy(cooperativeStatus = "Waiting for calibrated camera observations")
        try {
            vision?.start(
                { messages.trySend(Message.Observations(token, it)) },
                { messages.trySend(Message.VisionError(token, it)) },
            )
        } catch (error: Exception) {
            stopCooperative()
            throw error
        }
        cooperativeTimer = blocScope.launch {
            while (isActive) {
                messages.send(Message.CooperativeTick(token))
                delay(100)
            }
        }
    }

    private fun stopCooperative() {
        cooperativeGeneration++
        cooperativeTimer?.cancel()
        cooperativeTimer = null
        cooperativeJob?.cancel()
        cooperativeJob = null
        observations = null
        cooperativeEstimate = null
        cooperativeFilter = null
        visionFailure = null
        lastObservationTime = Double.NEGATIVE_INFINITY
        lastCooperativeCapture = Double.NEGATIVE_INFINITY
        try {
            vision?.stop()
        } catch (error: Exception) {
            mutableState.value = state.value.copy(error = "Unable to stop edge vision resources")
            logger.error("Unable to stop edge vision", error)
        }
    }

    private fun observe(value: ObservedPeers) {
        if (!state.value.sections.stage2 || session == null) return
        require(value.captureMonotonicSeconds.isFinite() &&
            clock.monotonicSeconds() - value.captureMonotonicSeconds in 0.0..0.5) { "Stale or future vision frame" }
        if (value.captureMonotonicSeconds <= lastObservationTime) return
        require(value.observations.size <= 8 &&
            value.observations.map { it.observationId }.distinct().size == value.observations.size)
        value.observations.forEach { it.validate() }
        lastObservationTime = value.captureMonotonicSeconds
        visionFailure = null
        observations = value.copy(observations = value.observations.toList())
    }

    private fun syncPeers() {
        val now = clock.monotonicSeconds()
        expireCooperative(now)
        val active = session ?: return
        if (!state.value.sections.stage2 || cooperativeJob != null) return
        val baseline = state.value.autonomousEstimate
        val reference = guard.reference?.takeIf {
            now - it.atMonotonic in 0.0..60.0 && it.uncertaintyMs <= 100
        } ?: run {
            mutableState.value = state.value.copy(cooperativeStatus =
                visionFailure ?: "Waiting for precise authenticated server time")
            return
        }
        val frame = observations?.takeIf { now - it.captureMonotonicSeconds in 0.0..0.3 &&
            it.captureMonotonicSeconds >= (baseline?.monotonicSeconds ?: Double.NEGATIVE_INFINITY) &&
            it.captureMonotonicSeconds > lastCooperativeCapture }
            ?: ObservedPeers(now, emptyList())
        val controls = controls(now)
        val freshGnss = lastRaw?.let { now - it.monotonicSeconds in 0.0..1.0 } == true && baseline != null
        val snapshot = if (freshGnss) filter.fork() else cooperativeFilter?.fork() ?: filter.fork()
        val predicted = snapshot.predict(frame.captureMonotonicSeconds, controls.first, controls.second, true)
            ?: baseline?.takeIf { frame.captureMonotonicSeconds == it.monotonicSeconds } ?: return
        val activeFlags = flags(now, baseline?.source)
        val independent = lastRaw?.let {
            it.source != LocationSource.network && !it.isMock && now - it.monotonicSeconds in 0.0..1.0
        } == true && baseline?.source in setOf(LocationSource.fused, LocationSource.gps) &&
            activeFlags.none { it in setOf("SUSPECTED_SPOOFING", "SUSPECTED_INTERFERENCE", "deviceTimeMismatch") }
        val peerState = CooperativeState(predicted.latitude, predicted.longitude, predicted.accuracyMeters,
            predicted.speedMps.coerceAtLeast(0.0), predicted.bearingDegrees,
            if (independent) (1.0 - predicted.accuracyMeters / 50.0).coerceIn(0.0, 0.95) else 0.0,
            vehicleClass, activeFlags.sorted())
        val timestamp = (reference.serverTimeMs + (frame.captureMonotonicSeconds - reference.atMonotonic) * 1000).toLong()
        val token = cooperativeGeneration
        cooperativeJob = blocScope.launch {
            var failure: Exception? = null
            val donors = try {
                withTimeout(500) { requireNotNull(cooperativeTransport).syncPeers(active, timestamp, peerState, frame.observations) }
            } catch (timeout: TimeoutCancellationException) {
                failure = timeout
                emptyList()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
                emptyList()
            }
            messages.send(Message.CooperativeResult(token, snapshot, frame, timestamp, donors, failure))
        }
    }

    private suspend fun cooperativeCompleted(result: Message.CooperativeResult) {
        if (result.token != cooperativeGeneration || !state.value.sections.stage2 || session == null) return
        cooperativeJob = null
        if (result.error != null) {
            cooperativeEstimate = null
            mutableState.value = state.value.copy(estimate = state.value.autonomousEstimate,
                cooperativeStatus = "Cooperative sync failed: ${result.error.message}")
            logger.error("Cooperative sync failed", result.error)
            return
        }
        val capture = result.observations.captureMonotonicSeconds
        val now = clock.monotonicSeconds()
        if (now - capture !in 0.0..0.5 || capture <= lastCooperativeCapture ||
            state.value.autonomousEstimate?.let { it.monotonicSeconds > capture } == true) return
        require(result.donors.size <= 3 && result.donors.map { it.observationId }.distinct().size == result.donors.size &&
            result.donors.map { it.donorId }.distinct().size == result.donors.size) { "Invalid cooperative donor assignments" }
        var estimate: TrackEstimate? = null
        for (donor in result.donors) {
            require(donor.captureTimestampMs == result.timestamp) { "Mismatched donor timestamp" }
            val observation = result.observations.observations.singleOrNull { it.observationId == donor.observationId }
                ?: error("Unsolicited donor")
            val speedBound = (state.value.autonomousEstimate?.speedMps ?: 0.0).coerceAtLeast(0.0) + donor.speedMps
            val timeVariance = (speedBound * (guard.reference?.uncertaintyMs ?: 100.0) / 1000).pow(2)
            val corrected = result.snapshot.cooperativeFix(donor, observation.copy(
                visionVarianceMeters2 = observation.visionVarianceMeters2 + timeVariance), capture)
            if (corrected != null) estimate = corrected
        }
        if (estimate != null) {
            lastCooperativeCapture = capture
            cooperativeEstimate = estimate
            cooperativeFilter = result.snapshot
            mutableState.value = state.value.copy(cooperativeStatus = null)
            record(estimate, null)
        } else {
            mutableState.value = state.value.copy(cooperativeStatus = visionFailure ?:
                if (result.observations.observations.isEmpty()) "Waiting for calibrated camera observations"
                else "No geometrically confirmed donor passed the innovation gate")
        }
    }

    private suspend fun handle(event: TrackerEvent) {
        when (event) {
            is TrackerEvent.Start -> start(event.session)
            TrackerEvent.Stop -> {
                stopCooperative()
                generation++
                flushJob?.cancel()
                flushJob = null
                session = null
                mutableState.value = state.value.copy(running = false)
            }
            is TrackerEvent.PlatformError -> fail(event.message, null)
            else -> if (session != null) {
                when (event) {
                    TrackerEvent.Tick -> tick()
                    is TrackerEvent.Position -> position(event.fix)
                    is TrackerEvent.Motion -> if (state.value.sections.stage1) motion(event.sample)
                    is TrackerEvent.ActivityChanged -> if (state.value.sections.stage1) activity(event.activity)
                    is TrackerEvent.Steps -> if (state.value.sections.stage1) steps(event)
                    is TrackerEvent.Gnss -> if (state.value.sections.stage1 && !guard.evidence(event.evidence)) fail("Invalid GNSS evidence", null)
                }
            }
        }
    }

    private suspend fun start(value: TrackingSession) {
        val old = session
        if (old == value && state.value.running) return
        generation++
        stopCooperative()
        flushJob?.cancel()
        flushJob = null
        session = value
        lastFlush = Double.NEGATIVE_INFINITY
        if (old?.identity != value.identity) {
            filter = TrackingFilter(config)
            throttle = TrackThrottle(config)
            guard = TimeGuard()
            stop = StopAnchor()
            clearPark()
            lastRaw = null
            lastRecorded = null
            lastPreciseTime = Double.NEGATIVE_INFINITY
            motion = null
            quietSamples = 0
            wakeSamples = 0
            gyroBias = 0.0
            previousPressure = null
            pressureRate = 0.0
            movingSince = null
            compassOffset = 0.0
            pendingStill = false
            resetMotionEvidence()
            mutableState.value = TrackerState(running = true, identity = value.identity, sections = state.value.sections)
        } else {
            mutableState.value = state.value.copy(running = true, identity = value.identity)
        }
        mutableState.value = state.value.copy(queuedPoints = queue.size(value.identity))
        if (state.value.sections.stage2) startCooperative()
    }

    private suspend fun position(fix: LocationFix) {
        val now = clock.monotonicSeconds()
        if (fix.source == LocationSource.network) {
            mutableState.value = state.value.copy(networkFixes = state.value.networkFixes + 1)
        }
        val age = now - fix.monotonicSeconds
        if (!age.isFinite() || age <= -2.0 || age > 10.0) return reject("Fix outside -2..10 second age window")
        if (!state.value.sections.stage1) {
            require(listOf(fix.latitude, fix.longitude, fix.accuracyMeters, fix.speedMps, fix.bearingDegrees).all { it.isFinite() } &&
                abs(fix.latitude) <= 90 && abs(fix.longitude) <= 180 && fix.accuracyMeters > 0) { "Invalid raw position" }
            if (lastRaw?.let { fix.monotonicSeconds <= it.monotonicSeconds } == true) return
            lastRaw = fix
            mutableState.value = state.value.copy(estimate = rawEstimate(fix), flags = emptySet(), rejection = null)
            return
        }
        if (!fix.isPreciseFix() && now - lastPreciseTime <= 10.0) {
            return reject(if (fix.source == LocationSource.network) "Network fix superseded by recent GNSS"
                else "Coarse fix superseded by recent precise fix")
        }
        if (fix.source != LocationSource.network && guard.suspect(now)) return reject("GNSS time disagrees with server")
        val controls = controls(now)
        val estimate = filter.fix(fix, controls.first, controls.second, state.value.activity == Activity.IN_VEHICLE,
            guard.strongCn0(fix.monotonicSeconds))
            ?: return reject(filter.rejection ?: "Rejected fix")
        lastRaw = fix
        if (fix.isPreciseFix()) {
            lastPreciseTime = fix.monotonicSeconds
            notePreciseMotion(fix)
        }
        mutableState.value = state.value.copy(acceptedFixes = state.value.acceptedFixes + 1, rejection = null)
        if (state.value.activity == Activity.WALKING && fix.speedMps > 0.7 && fix.bearingDegrees >= 0) {
            freshCompass(now)?.let { compassOffset = wrapRadians((fix.bearingDegrees - it) * RADIANS) / RADIANS }
        }
        if (stop.shouldRelease(fix)) {
            stop = StopAnchor()
            clearPark()
        }
        if (!stop.active && quietMotion(now) && fix.speedMps in 0.0..<0.5 &&
            fix.source != LocationSource.network && slowSpeedConfirmed() && !pressureVeto(now)) {
            filter.stationary()
            stop.begin(parkHold ?: estimate)
            clearPark()
        }
        if (!stop.active) {
            val hadSamples = park.hasSamples()
            val confirmed = park.observe(fix)
            if (!hadSamples && park.hasSamples()) parkHold = estimate.copy(speedMps = 0.0)
            if (confirmed) {
                filter.stationary()
                val origin = parkHold ?: estimate
                clearPark()
                stop.begin(origin.copy(monotonicSeconds = estimate.monotonicSeconds))
            } else if (!park.hasSamples()) {
                parkHold = null
            }
        }
        if (state.value.activity == Activity.STILL && !stop.active) {
            sensors.setStationary(false)
            mutableState.value = state.value.copy(activity = Activity.UNKNOWN)
        }
        val pinned = if (!stop.active) parkHold?.takeIf { park.pinned() } else null
        if (stop.active) {
            stop.driftMeters(fix)?.let { drift ->
                val old = state.value
                val count = old.stationaryDriftSamples + 1
                mutableState.value = old.copy(stationaryDriftSamples = count,
                    stationaryDriftMeanMeters = old.stationaryDriftMeanMeters +
                        (drift - old.stationaryDriftMeanMeters) / count,
                    stationaryDriftMaxMeters = max(old.stationaryDriftMaxMeters, drift))
            }
            stop.add(fix)
            record(heldEstimate(now) ?: estimate, null)
        } else {
            record(pinned?.copy(monotonicSeconds = estimate.monotonicSeconds) ?: estimate, fix)
        }
        if (pendingStill) {
            pendingStill = false
            activity(Activity.STILL)
        }
    }

    private suspend fun tick() {
        if (!state.value.sections.stage1) return
        val now = clock.monotonicSeconds()
        expireCooperative(now)
        val raw = lastRaw
        if (stop.active) {
            heldEstimate(now)?.let { record(it, null) }
        } else if (raw != null && now - raw.monotonicSeconds > 2.0 &&
            state.value.activity != Activity.WALKING && state.value.activity != Activity.STILL) {
            // Quiet IMU alone cannot distinguish a parked car from constant-speed driving.
            if (quietMotion(now) && raw.speedMps in 0.0..<0.5 && slowSpeedConfirmed() && !pressureVeto(now) &&
                now - raw.monotonicSeconds <= config.predictionHorizonSeconds) {
                val held = state.value.autonomousEstimate
                if (held != null) {
                    filter.stationary()
                    stop.begin(held.copy(monotonicSeconds = now), raw.source == LocationSource.network)
                    heldEstimate(now)?.let { record(it, null) }
                } else {
                    filter.stationary()
                    predict(now)
                }
            } else {
                predict(now)
            }
        }
        if (!stop.active && raw != null && now - raw.monotonicSeconds > config.predictionHorizonSeconds) {
            val cooperative = freshCooperative(now)
            mutableState.value = state.value.copy(estimate = cooperative, autonomousEstimate = null,
                rejection = if (cooperative == null) "No reliable position: prediction horizon exceeded" else null)
        }
        val speed = state.value.estimate?.speedMps ?: 0.0
        if (now - lastFlush >= throttle.flushIntervalSeconds(speed) && flushJob == null) scheduleFlush(now)
        val source = state.value.estimate?.source
        mutableState.value = state.value.copy(flags = flags(now, source))
    }

    private fun heldEstimate(now: Double) =
        stop.estimate(now, lastPreciseTime.takeIf { it.isFinite() && it > 0.0 })

    private suspend fun predict(now: Double) {
        val controls = controls(now)
        val estimate = filter.predict(now, controls.first, controls.second, state.value.activity == Activity.IN_VEHICLE)
        if (estimate == null) {
            val cooperative = freshCooperative(now)
            mutableState.value = state.value.copy(estimate = cooperative, autonomousEstimate = null,
                rejection = if (cooperative == null) "Prediction exceeds time or uncertainty limit" else null)
        } else {
            record(estimate, null)
        }
    }

    private fun freshCooperative(now: Double): TrackEstimate? =
        cooperativeEstimate?.takeIf { state.value.sections.stage2 && now - it.monotonicSeconds in 0.0..0.5 }

    private fun expireCooperative(now: Double) {
        if (cooperativeEstimate?.let { now - it.monotonicSeconds > 0.5 } == true) {
            cooperativeEstimate = null
            mutableState.value = state.value.copy(estimate = state.value.autonomousEstimate,
                cooperativeStatus = visionFailure ?: "No fresh confirmed donor")
        }
    }

    private fun motion(sample: MotionSample) {
        if (!(listOf(sample.monotonicSeconds, sample.linearAccelerationMagnitude) +
                listOfNotNull(sample.longitudinalAcceleration, sample.yawRateRadians)).all { it.isFinite() } ||
            sample.linearAccelerationMagnitude < 0) {
            reject("Invalid motion sample")
            return
        }
        if (clock.monotonicSeconds() - sample.monotonicSeconds !in 0.0..1.0) {
            reject("Stale or future motion sample")
            return
        }
        if (motion?.let { sample.monotonicSeconds <= it.monotonicSeconds } == true) return
        val quiet = sample.linearAccelerationMagnitude < 0.2
        quietSamples = if (quiet) min(quietSamples + 1, 1000) else 0
        val yawRate = sample.yawRateRadians
        val stationaryEvidence = stop.active || state.value.activity == Activity.STILL || lastRaw?.let {
            it.source != LocationSource.network && it.speedMps in 0.0..<0.5 &&
                sample.monotonicSeconds - it.monotonicSeconds in 0.0..5.0
        } == true
        if (quiet && stationaryEvidence && yawRate != null && abs(yawRate - gyroBias) < 0.05) {
            gyroBias = 0.98 * gyroBias + 0.02 * yawRate
        }
        sample.pressureHpa?.takeIf { it.isFinite() && it > 0 }?.let { pressure ->
            previousPressure?.let { (time, previous) ->
                val dt = sample.monotonicSeconds - time
                if (dt > 0) pressureRate = (pressure - previous) / dt
            }
            previousPressure = sample.monotonicSeconds to pressure
        }
        motion = sample
        filter.observeMotion(sample.monotonicSeconds, sample.longitudinalAcceleration,
            sample.yawRateRadians?.minus(gyroBias))
        cooperativeFilter?.observeMotion(sample.monotonicSeconds, sample.longitudinalAcceleration,
            sample.yawRateRadians?.minus(gyroBias))
        if (sample.linearAccelerationMagnitude > 0.8) {
            wakeSamples++
            if (movingSince == null) movingSince = sample.monotonicSeconds
        } else {
            wakeSamples = 0
            movingSince = null
        }
        if (state.value.activity == Activity.STILL && wakeSamples >= 3) {
            sensors.setStationary(false)
            mutableState.value = state.value.copy(activity = Activity.UNKNOWN)
        }
        val moving = movingSince
        if (stop.active && moving != null && sample.monotonicSeconds - moving >= 5.0 &&
            sample.monotonicSeconds - lastPreciseTime > 5.0) {
            stop = StopAnchor()
            clearPark()
        }
    }

    private fun clearPark() {
        park = ParkedMotion()
        parkHold = null
    }

    private fun activity(value: Activity) {
        if (value == Activity.STILL && pressureVeto(clock.monotonicSeconds())) {
            pendingStill = true
            return
        }
        if (value == Activity.STILL && !stop.active &&
            (lastRaw == null || state.value.estimate == null ||
                clock.monotonicSeconds() - (lastRaw?.monotonicSeconds ?: 0.0) > config.predictionHorizonSeconds)) {
            pendingStill = true
            return
        }
        if (value == Activity.STILL && lastRaw?.let {
                it.source != LocationSource.network && it.speedMps > 1.0 &&
                    clock.monotonicSeconds() - it.monotonicSeconds <= 5.0
            } == true) {
            pendingStill = true
            return
        }
        if (value != Activity.STILL) pendingStill = false
        if (value == state.value.activity) return
        if (value == Activity.STILL) {
            filter.stationary()?.let { estimate ->
                stop.begin(estimate.copy(monotonicSeconds = clock.monotonicSeconds()),
                    lastRaw?.source == LocationSource.network)
            }
        }
        sensors.setStationary(value == Activity.STILL)
        mutableState.value = state.value.copy(activity = value)
    }

    private suspend fun steps(event: TrackerEvent.Steps) {
        if (state.value.activity != Activity.WALKING || stop.active) return
        if (event.count !in 1..10) return reject("Step count outside 1..10")
        val now = clock.monotonicSeconds()
        val observed = event.monotonicSeconds ?: return reject("Step observation time missing")
        if (!observed.isFinite() || now - observed !in 0.0..2.0) return reject("Stale step observation")
        val lastFix = lastRaw ?: return
        if (now - lastFix.monotonicSeconds <= 2.0) return
        val compass = freshCompass(now) ?: return reject("Walking prediction requires fresh compass heading")
        filter.pedestrianSteps(observed, event.count, compass + compassOffset)?.let { record(it, null) }
    }

    private fun freshCompass(now: Double): Double? =
        motion?.takeIf { now - it.monotonicSeconds in 0.0..2.0 }?.compassDegrees?.takeIf { it.isFinite() }

    private fun controls(now: Double): Pair<Double, Double> =
        motion?.takeIf { now - it.monotonicSeconds in 0.0..1.0 }?.let {
            (it.longitudinalAcceleration ?: 0.0) to (it.yawRateRadians?.minus(gyroBias) ?: 0.0)
        } ?: (0.0 to 0.0)

    private fun resetMotionEvidence() {
        sawPreciseMotion = false
        consecutiveSlowPreciseFixes = 0
    }

    private fun notePreciseMotion(fix: LocationFix) {
        if (fix.speedMps >= 0.5) {
            sawPreciseMotion = true
            consecutiveSlowPreciseFixes = 0
        } else if (fix.speedMps >= 0.0) {
            consecutiveSlowPreciseFixes++
        }
    }

    /** A single slow Doppler sample after real motion is a dropout, not a stop. */
    private fun slowSpeedConfirmed() = !sawPreciseMotion || consecutiveSlowPreciseFixes >= 2

    private fun quietMotion(now: Double): Boolean =
        quietSamples >= 10 && motion?.let { now - it.monotonicSeconds in 0.0..1.0 } == true

    private fun pressureVeto(now: Double): Boolean =
        previousPressure?.let { now - it.first in 0.0..2.0 && abs(pressureRate) >= 0.02 } == true

    private fun flags(now: Double, source: LocationSource?): Set<String> = buildSet {
        addAll(guard.flags(now, clock.wallTimeMs(), source != LocationSource.network))
        if (source == LocationSource.network || lastRaw?.source == LocationSource.network) add("networkFallback")
        if (!stop.active && lastRaw?.let { now - it.monotonicSeconds > config.predictionHorizonSeconds } == true) {
            add(if (source == LocationSource.cooperative) "gnssStale" else "positionStale")
        }
        if (source == LocationSource.cooperative) add("cooperative")
        if (source == LocationSource.deadReckoning) {
            add("predicted")
            if (motion?.let { now - it.monotonicSeconds > 1.0 ||
                    it.longitudinalAcceleration == null || it.yawRateRadians == null } != false) add("imuUnavailable")
        }
    }

    private suspend fun record(estimate: TrackEstimate, raw: LocationFix?) {
        val active = session ?: return
        val now = clock.monotonicSeconds()
        val activeFlags = flags(now, estimate.source)
        mutableState.value = state.value.copy(estimate = estimate, flags = activeFlags,
            autonomousEstimate = if (estimate.source == LocationSource.cooperative)
                state.value.autonomousEstimate else estimate)
        if (!throttle.shouldRecord(estimate)) return
        val matched = mapMatcher?.match(estimate) ?: estimate
        require(listOf(matched.latitude, matched.longitude, matched.accuracyMeters, matched.speedMps,
            matched.bearingDegrees).all { it.isFinite() } && abs(matched.latitude) <= 90 &&
            abs(matched.longitude) <= 180 && matched.accuracyMeters > 0) { "Invalid map matching result" }
        val point = TrackPoint(
            id = idGenerator.newId(),
            rawLatitude = raw?.latitude, rawLongitude = raw?.longitude, rawAccuracyMeters = raw?.accuracyMeters,
            smoothedLatitude = matched.latitude, smoothedLongitude = matched.longitude,
            smoothedAccuracyMeters = matched.accuracyMeters,
            speedMps = matched.speedMps.takeUnless { matched.source == LocationSource.network },
            bearingDegrees = matched.bearingDegrees.takeUnless { matched.source == LocationSource.network },
            deviceTimeMs = clock.wallTimeMs(),
            observedAtMs = raw?.observedAtMs ?: clock.wallTimeMs(),
            source = matched.source, isMock = raw?.isMock ?: lastRaw?.isMock ?: false,
            flags = activeFlags.sorted(),
        )
        require(point.id.matches(Regex("[0-9a-f]{32}"))) { "Point ID must be 16 secure random bytes in hex" }
        queue.append(active.identity, point)
        throttle.recorded(estimate)
        lastRecorded = estimate
        mutableState.value = state.value.copy(queuedPoints = state.value.queuedPoints + 1)
    }

    private fun scheduleFlush(now: Double) {
        val active = session ?: return
        val token = generation
        val needsTime = guard.needsReference(now)
        lastFlush = now
        flushJob = blocScope.launch {
            var reference: TimeReference? = null
            var batch = emptyList<TrackPoint>()
            var accepted = emptyList<String>()
            var failure: Exception? = null
            try {
                withTimeout(30_000) {
                    if (needsTime) {
                        val start = clock.monotonicSeconds()
                        val server = transport.serverTime(active)
                        val end = clock.monotonicSeconds()
                        require(end >= start && server.serverTimeMs > 0) { "Invalid server time sample" }
                        reference = TimeReference(server.serverTimeMs, (start + end) / 2, (end - start) * 500)
                    }
                    batch = queue.peek(active.identity, 100)
                    if (batch.isNotEmpty()) accepted = transport.ingest(active, batch)
                }
            } catch (timeout: TimeoutCancellationException) {
                failure = timeout
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
            }
            messages.send(Message.Flushed(token, active, batch, accepted, reference, failure))
        }
    }

    private suspend fun completed(result: Message.Flushed) {
        if (result.generation != generation || result.session != session) return
        flushJob = null
        result.reference?.let { guard.reference = it }
        if (result.error != null) {
            fail("Upload failed", result.error)
            return
        }
        val submitted = result.batch.map { it.id }.toSet()
        val accepted = result.acceptedIds.toSet()
        require(accepted.size == result.acceptedIds.size && accepted.all { it in submitted }) { "Invalid acceptedIds response" }
        val prefix = result.batch.takeWhile { it.id in accepted }.map { it.id }
        if (result.batch.isNotEmpty()) {
            require(prefix.isNotEmpty()) { "Server did not accept the FIFO head" }
            queue.acknowledge(result.session.identity, prefix)
        }
        mutableState.value = state.value.copy(
            queuedPoints = queue.size(result.session.identity),
            lastUploadTimeMs = clock.wallTimeMs(), error = null,
        )
    }

    private fun reject(reason: String) {
        mutableState.value = state.value.copy(rejection = reason, rejectedFixes = state.value.rejectedFixes + 1)
    }

    private fun fail(message: String, cause: Throwable?) {
        logger.error(message, cause)
        mutableState.value = state.value.copy(error = if (cause == null) message else "$message: ${cause.message}")
    }
}
