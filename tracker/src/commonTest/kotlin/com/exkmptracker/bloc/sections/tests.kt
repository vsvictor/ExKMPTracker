package com.exkmptracker.bloc.sections

import com.exkmptracker.bloc.*
import com.exkmptracker.cooperative.*
import com.exkmptracker.model.*
import com.exkmptracker.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.math.PI
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SectionTests {
    private class Clock : TrackerClock {
        var now = 1.0
        override fun monotonicSeconds() = now
        override fun wallTimeMs() = (now * 1_000).toLong()
    }
    private class Vision : EdgeVisionController {
        var starts = 0
        var stops = 0
        lateinit var emit: (ObservedPeers) -> Unit
        lateinit var error: (String) -> Unit
        override fun start(onObservations: (ObservedPeers) -> Unit, onError: (String) -> Unit) {
            starts++
            emit = onObservations
            error = onError
        }
        override fun stop() { stops++ }
    }
    private class Peers : CooperativeTransport {
        var calls = 0
        var cancelled = false
        var block = false
        var fixedLatitude: Double? = null
        override suspend fun syncPeers(session: TrackingSession, captureTimestampMs: Long,
            state: CooperativeState, observations: List<VisualObservation>): List<MatchedDonor> {
            calls++
            if (block) {
                try { awaitCancellation() } finally { cancelled = true }
            }
            return observations.take(1).map {
                MatchedDonor(it.observationId, "peer", (fixedLatitude ?: state.latitude) + 22.0 / 6_371_000 * 180 / PI,
                    state.longitude, 2.0, 0.0, 0.0, 0.9, it.vehicleClass, captureTimestampMs)
            }
        }
    }
    private class Fixture(
        scope: CoroutineScope,
        configured: Boolean = true,
        vehicleClass: VehicleClass? = VehicleClass.CAR,
    ) {
        val clock = Clock()
        val vision = Vision()
        val peers = Peers()
        val points = mutableListOf<TrackPoint>()
        val session = TrackingSession.fromConfirmedShift("session", "driver", "brand")
        val bloc = TrackerBloc(scope, clock,
            object : TrackQueueStorage {
                override suspend fun append(identity: TrackingIdentity, point: TrackPoint) { points.add(point) }
                override suspend fun peek(identity: TrackingIdentity, limit: Int) = points.take(limit)
                override suspend fun acknowledge(identity: TrackingIdentity, ids: List<String>) { points.removeAll { it.id in ids } }
                override suspend fun size(identity: TrackingIdentity) = points.size.toLong()
            },
            object : TrackingTransport {
                override suspend fun serverTime(session: TrackingSession) = ServerTime(clock.wallTimeMs())
                override suspend fun ingest(session: TrackingSession, points: List<TrackPoint>) = points.map { it.id }
            },
            object : PointIdGenerator {
                var id = 0
                override fun newId() = (++id).toString(16).padStart(32, '0')
            },
            object : TrackerLogger { override fun error(message: String, cause: Throwable?) {} },
            object : PlatformSensorProvider { override fun setStationary(stationary: Boolean) {} },
            cooperativeTransport = if (configured) peers else null,
            vision = if (configured) vision else null,
            vehicleClass = vehicleClass)
        fun fix(latitude: Double = 50.0) = bloc.dispatch(TrackerEvent.Position(
            LocationFix(latitude, 30.0, 15.0, clock.wallTimeMs(), clock.now, LocationSource.gps, 0.0, 0.0)))
        fun frame() = vision.emit(ObservedPeers(clock.now,
            listOf(VisualObservation("v", VehicleClass.CAR, 20.0, 0.0, null, 1.0))))
    }

    @Test fun missingEgoClassIsNotPublishedAsCar() = runTest {
        val f = Fixture(backgroundScope, vehicleClass = null)
        f.bloc.dispatch(TrackerEvent.Start(f.session))
        f.fix()
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        f.bloc.section(2, true)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(f.bloc.state.value.sections.stage1)
        assertEquals(0, f.peers.calls)
        assertEquals("Ego vehicle class is not set", f.bloc.state.value.cooperativeStatus)
    }

    @Test fun defaultsAndUnsupportedStageAreExplicit() = runTest {
        val f = Fixture(backgroundScope, false)
        assertEquals(TrackerSections(true, false, false), f.bloc.state.value.sections)
        assertFailsWith<IllegalArgumentException> { f.bloc.section(3, true) }
        assertFailsWith<IllegalStateException> { f.bloc.section(2, true) }
        assertFalse(f.bloc.state.value.sections.stage2)
        assertNotNull(f.bloc.state.value.error)
        assertEquals(0, f.vision.starts)
    }

    @Test fun disabledStageOnePassesLiveRawAndDoesNotRecord() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(f.session))
        f.bloc.section(1, false)
        f.fix()
        runCurrent()
        assertEquals(50.0, f.bloc.state.value.estimate?.latitude)
        f.clock.now = 2.0
        f.fix(51.0)
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        assertEquals(51.0, f.bloc.state.value.estimate?.latitude)
        assertTrue(f.points.isEmpty())
        assertFailsWith<IllegalStateException> { f.bloc.section(2, true) }
        f.bloc.section(1, true)
        f.clock.now = 3.0
        f.fix(51.0)
        runCurrent()
        assertNotNull(f.bloc.state.value.autonomousEstimate)
        assertFalse(f.bloc.state.value.sections.stage2)
    }

    @Test fun disablingStageTwoReturnsUncontaminatedLiveBaselineAndDiscardsOldFrames() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(f.session))
        f.fix()
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        f.bloc.section(2, true)
        runCurrent()
        f.clock.now = 1.1
        f.frame()
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(LocationSource.cooperative, f.bloc.state.value.estimate?.source)
        assertEquals(50.0, f.bloc.state.value.autonomousEstimate?.latitude)
        f.bloc.section(2, false)
        assertEquals(50.0, f.bloc.state.value.estimate?.latitude)
        val count = f.peers.calls
        f.clock.now = 2.0
        f.frame()
        f.fix(50.00001)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertTrue(assertNotNull(f.bloc.state.value.estimate).latitude > 50.0)
        assertEquals(count, f.peers.calls)
        assertTrue(f.vision.stops > 0)
    }

    @Test fun disablingCancelsInFlightRpcAndStoppingDoesNotRestartVision() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(f.session))
        f.fix()
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        f.peers.block = true
        f.bloc.section(2, true)
        runCurrent()
        assertEquals(1, f.peers.calls)
        f.bloc.section(1, false)
        runCurrent()
        assertTrue(f.peers.cancelled)
        assertFalse(f.bloc.state.value.sections.stage2)
        f.bloc.dispatch(TrackerEvent.Stop)
        runCurrent()
        assertFalse(f.bloc.state.value.running)
        assertEquals(1, f.vision.starts)
    }

    @Test fun liveDonorsContinueBeyondAutonomousHorizonButNeverLeakIntoFallback() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(f.session))
        f.fix()
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        f.peers.fixedLatitude = 50.0
        f.bloc.section(2, true)
        runCurrent()
        repeat(210) { index ->
            f.clock.now = 1.1 + index * 0.1
            if (index % 10 == 0) f.bloc.dispatch(TrackerEvent.Tick)
            f.frame()
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
        }
        assertNull(f.bloc.state.value.autonomousEstimate)
        assertEquals(LocationSource.cooperative, f.bloc.state.value.estimate?.source)
        f.bloc.section(2, false)
        assertNull(f.bloc.state.value.estimate)
        assertNull(f.bloc.state.value.autonomousEstimate)
    }

    @Test fun visionUnavailableReasonSurvivesHeartbeatsAndGnssContinues() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(f.session))
        f.fix()
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        assertEquals(0, f.vision.starts)
        assertEquals(0, f.peers.calls)
        f.bloc.section(2, true)
        runCurrent()
        f.vision.error("Licensed model was not supplied")
        runCurrent()
        f.clock.now = 1.1
        advanceTimeBy(200)
        runCurrent()
        assertEquals("Licensed model was not supplied", f.bloc.state.value.cooperativeStatus)
        assertEquals(LocationSource.gps, f.bloc.state.value.estimate?.source)
    }

    @Test fun staleCooperativeOutputExpiresEvenWithoutNativeTicksOrNetworkResponse() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(f.session))
        f.fix()
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        f.bloc.section(2, true)
        runCurrent()
        f.clock.now = 1.1
        f.frame()
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(LocationSource.cooperative, f.bloc.state.value.estimate?.source)
        f.peers.block = true
        f.clock.now = 1.2
        advanceTimeBy(100)
        runCurrent()
        f.clock.now = 1.8
        advanceTimeBy(100)
        runCurrent()
        assertEquals(LocationSource.gps, f.bloc.state.value.estimate?.source)
        assertEquals(50.0, f.bloc.state.value.estimate?.latitude)
    }
}
