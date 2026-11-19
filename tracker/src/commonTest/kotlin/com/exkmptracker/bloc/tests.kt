package com.exkmptracker.bloc

import com.exkmptracker.model.*
import com.exkmptracker.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerBlocTests {
    private val session = TrackingSession.fromConfirmedShift("session", "driver", "brand")

    private class Clock : TrackerClock {
        var now = 1.0
        override fun wallTimeMs() = (now * 1000).toLong()
        override fun monotonicSeconds() = now
    }

    private class Queue : TrackQueue {
        val points = mutableMapOf<TrackingIdentity, MutableList<TrackPoint>>()
        var failAppend = false
        override suspend fun append(identity: TrackingIdentity, point: TrackPoint) {
            check(!failAppend) { "Disk full" }
            points.getOrPut(identity) { mutableListOf() }.add(point)
        }
        override suspend fun peek(identity: TrackingIdentity, limit: Int) = points[identity].orEmpty().take(limit)
        override suspend fun acknowledge(identity: TrackingIdentity, ids: List<String>) {
            val queue = points.getValue(identity)
            check(queue.take(ids.size).map { it.id } == ids)
            repeat(ids.size) { queue.removeAt(0) }
        }
        override suspend fun size(identity: TrackingIdentity) = points[identity].orEmpty().size.toLong()
    }

    private class Transport : TrackingTransport {
        var accepted: ((List<TrackPoint>) -> List<String>) = { points -> points.map { it.id } }
        var beforeResponse: (suspend () -> Unit)? = null
        var calls = 0
        override suspend fun serverTime(session: TrackingSession) = ServerTime(1000)
        override suspend fun ingest(session: TrackingSession, points: List<TrackPoint>): List<String> {
            calls++
            beforeResponse?.invoke()
            return accepted(points)
        }
    }

    private class Fixture(scope: CoroutineScope) {
        val clock = Clock()
        val queue = Queue()
        val transport = Transport()
        val logged = mutableListOf<String>()
        var nextId = 0
        val bloc = TrackerBloc(scope, clock, queue, transport,
            object : PointIdGenerator { override fun newId() = (++nextId).toString(16).padStart(32, '0') },
            object : TrackerLogger { override fun error(message: String, cause: Throwable?) { logged.add(message) } },
            object : SensorController { override fun setStationary(stationary: Boolean) {} })
        fun fix(time: Double = clock.now, source: LocationSource = LocationSource.gps) {
            bloc.dispatch(TrackerEvent.Position(LocationFix(50.0, 30.0, 5.0,
                (time * 1000).toLong(), time, source, 0.0, 0.0)))
        }
    }

    @Test fun sensorsDoNotRecordBeforeConfirmedSession() = runTest {
        val f = Fixture(backgroundScope)
        f.fix()
        runCurrent()
        assertFalse(f.bloc.state.value.running)
        assertEquals(0, f.queue.size(session.identity))
    }

    @Test fun rejectsStaleFutureAndRedundantNetworkFixes() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix(time = -10.0)
        runCurrent()
        assertContains(assertNotNull(f.bloc.state.value.rejection), "age")
        f.fix(time = 4.0)
        runCurrent()
        assertEquals(0, f.bloc.state.value.acceptedFixes)
        f.fix()
        runCurrent()
        f.clock.now = 2.0
        f.fix(source = LocationSource.network)
        runCurrent()
        assertEquals(1, f.bloc.state.value.acceptedFixes)
        assertEquals(1, f.bloc.state.value.networkFixes)
    }

    @Test fun appendFailureDoesNotAdvanceThrottle() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.queue.failAppend = true
        f.fix()
        runCurrent()
        assertEquals(0, f.queue.size(session.identity))
        assertNotNull(f.bloc.state.value.error)
        f.queue.failAppend = false
        f.clock.now = 2.0
        f.fix()
        runCurrent()
        assertEquals(1, f.queue.size(session.identity))
    }

    @Test fun acknowledgesOnlyAcceptedFifoPrefix() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix()
        runCurrent()
        f.clock.now = 32.0
        f.fix()
        runCurrent()
        f.clock.now = 63.0
        f.fix()
        runCurrent()
        val original = f.queue.peek(session.identity, 100)
        f.transport.accepted = { listOf(it[0].id, it[2].id) }
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        assertEquals(original.drop(1).map { it.id }, f.queue.peek(session.identity, 100).map { it.id })
    }

    @Test fun unacceptedHeadOrForeignAcceptedIdLeavesQueueUntouched() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix()
        runCurrent()
        f.transport.accepted = { emptyList() }
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        assertEquals(1, f.queue.size(session.identity))
        assertNotNull(f.bloc.state.value.error)
        f.clock.now += 10
        f.transport.accepted = { listOf("foreign") }
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        assertEquals(1, f.queue.size(session.identity))
    }

    @Test fun slowNetworkDoesNotBlockSensorEvents() = runTest {
        val f = Fixture(backgroundScope)
        val response = CompletableDeferred<Unit>()
        f.transport.beforeResponse = { response.await() }
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix()
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        f.clock.now = 2.0
        f.fix()
        runCurrent()
        assertEquals(2, f.bloc.state.value.acceptedFixes)
        response.complete(Unit)
        runCurrent()
        assertEquals(0, f.queue.size(session.identity))
    }

    @Test fun sessionChangeCannotAcknowledgeOldOwnersPoints() = runTest {
        val f = Fixture(backgroundScope)
        val response = CompletableDeferred<Unit>()
        f.transport.beforeResponse = { withContext(NonCancellable) { response.await() } }
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix()
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        val newSession = TrackingSession.fromConfirmedShift("newSession", "newDriver", "brand")
        f.bloc.dispatch(TrackerEvent.Start(newSession))
        runCurrent()
        response.complete(Unit)
        runCurrent()
        assertEquals(1, f.queue.size(session.identity))
        assertEquals(newSession.identity, f.bloc.state.value.identity)
    }

    @Test fun sameIdentityStartDoesNotResetFilterOrThrottle() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix()
        runCurrent()
        f.bloc.dispatch(TrackerEvent.Start(session.copy(sessionId = "refreshed")))
        f.clock.now = 2.0
        f.fix()
        runCurrent()
        assertEquals(2, f.bloc.state.value.acceptedFixes)
        assertEquals(1, f.queue.size(session.identity))
    }

    @Test fun brandChangeUsesAnotherQueueEvenForSameDriver() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix()
        runCurrent()
        val otherBrand = session.copy(identity = TrackingIdentity("driver", "other-brand"))
        f.bloc.dispatch(TrackerEvent.Start(otherBrand))
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        assertEquals(1, f.queue.size(session.identity))
        assertEquals(0, f.transport.calls)
        assertEquals(otherBrand.identity, f.bloc.state.value.identity)
    }

    @Test fun stationaryHoldPreventsNoiseAndRestoresMotionOnWake() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        repeat(10) { i ->
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(i / 10.0, 0.0, 0.0, 0.01)))
        }
        f.fix()
        runCurrent()
        assertEquals(LocationSource.stationary, f.bloc.state.value.estimate?.source)
        f.bloc.dispatch(TrackerEvent.ActivityChanged(Activity.STILL))
        repeat(3) { i ->
            f.clock.now = 2.0 + i / 10.0
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(f.clock.now, 0.0, 0.0, 1.0)))
            runCurrent()
        }
        runCurrent()
        assertEquals(Activity.UNKNOWN, f.bloc.state.value.activity)
    }

    @Test fun parkedColdStartDoesNotRequireMovingCourseToDetectQuietImu() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        repeat(10) { i ->
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(i / 10.0, null, null, 0.01)))
        }
        f.fix()
        runCurrent()
        assertEquals(LocationSource.stationary, f.bloc.state.value.estimate?.source)
    }

    @Test fun walkingStepsRequireFreshObservationAndCannotDoubleCountFreshGnss() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.bloc.dispatch(TrackerEvent.ActivityChanged(Activity.WALKING))
        f.fix()
        f.bloc.dispatch(TrackerEvent.Motion(MotionSample(1.0, 0.0, 0.0, 0.3, compassDegrees = 90.0)))
        f.bloc.dispatch(TrackerEvent.Steps(1, 1.0))
        runCurrent()
        assertEquals(LocationSource.gps, f.bloc.state.value.estimate?.source)
        f.clock.now = 4.0
        f.bloc.dispatch(TrackerEvent.Motion(MotionSample(4.0, 0.0, 0.0, 0.3, compassDegrees = 90.0)))
        f.bloc.dispatch(TrackerEvent.Steps(10, 1.0))
        runCurrent()
        assertContains(assertNotNull(f.bloc.state.value.rejection), "Stale step")
        f.bloc.dispatch(TrackerEvent.Steps(1, 4.0))
        runCurrent()
        val estimated = assertNotNull(f.bloc.state.value.estimate)
        assertEquals(LocationSource.deadReckoning, estimated.source)
        assertEquals(0.75, com.exkmptracker.filter.distanceMeters(50.0, 30.0,
            estimated.latitude, estimated.longitude), 0.001)
    }

    @Test fun stillBeforeFirstFixDoesNotPreventInitialPosition() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.bloc.dispatch(TrackerEvent.ActivityChanged(Activity.STILL))
        f.fix()
        runCurrent()
        assertEquals(1, f.bloc.state.value.acceptedFixes)
        assertEquals(Activity.STILL, f.bloc.state.value.activity)
        assertEquals(1, f.queue.size(session.identity))
    }

    @Test fun quietImuAtDrivingSpeedDoesNotForceZeroVelocity() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.0, 30.0, 5.0, 1000, 1.0,
            LocationSource.gps, 20.0, 0.0)))
        runCurrent()
        repeat(10) { i ->
            f.clock.now = 1.0 + i / 10.0
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(f.clock.now, 0.0, 0.0, 0.01)))
            runCurrent()
        }
        f.clock.now = 4.0
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        assertEquals(LocationSource.deadReckoning, f.bloc.state.value.estimate?.source)
        assertEquals(20.0, assertNotNull(f.bloc.state.value.estimate).speedMps, 1e-8)
    }

    @Test fun stillContradictingRecentDrivingFixCannotPauseGnss() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.0, 30.0, 5.0, 1000, 1.0,
            LocationSource.gps, 20.0, 0.0)))
        f.bloc.dispatch(TrackerEvent.ActivityChanged(Activity.STILL))
        runCurrent()
        assertEquals(Activity.UNKNOWN, f.bloc.state.value.activity)
        assertEquals(20.0, assertNotNull(f.bloc.state.value.estimate).speedMps, 1e-8)
    }

    @Test fun gentleDrivingTurnCannotBeLearnedAsGyroBias() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.0, 30.0, 5.0, 1000, 1.0,
            LocationSource.gps, 20.0, 0.0, speedAccuracyMps = 0.1, bearingAccuracyDegrees = 0.5)))
        runCurrent()
        repeat(50) { i ->
            f.clock.now = 1.0 + i / 5.0
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(f.clock.now, 0.0, 0.008, 0.16)))
            runCurrent()
        }
        f.clock.now = 11.0
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        assertEquals(0.008 * 10 * 180 / kotlin.math.PI,
            assertNotNull(f.bloc.state.value.estimate).bearingDegrees, 0.001)
    }

    @Test fun failedOrTimedOutUploadIsRetryable() = runTest {
        val f = Fixture(backgroundScope)
        val never = CompletableDeferred<Unit>()
        f.transport.beforeResponse = { never.await() }
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix()
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertNotNull(f.bloc.state.value.error)
        assertEquals(1, f.queue.size(session.identity))
        f.transport.beforeResponse = null
        f.clock.now = 32.0
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        assertEquals(0, f.queue.size(session.identity))
        assertNull(f.bloc.state.value.error)
    }

    @Test fun ageAtFutureBoundaryIsRejected() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix(time = 3.0)
        runCurrent()
        assertEquals(0, f.bloc.state.value.acceptedFixes)
    }

    @Test fun expiredPredictionDoesNotAppearAsLivePosition() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix()
        runCurrent()
        f.clock.now = 17.0
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        assertNull(f.bloc.state.value.estimate)
        assertContains(f.bloc.state.value.flags, "positionStale")
    }

    @Test fun stillCannotTurnExpiredPositionIntoFreshAnchor() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix()
        runCurrent()
        f.clock.now = 17.0
        f.bloc.dispatch(TrackerEvent.Tick)
        f.bloc.dispatch(TrackerEvent.ActivityChanged(Activity.STILL))
        runCurrent()
        assertNull(f.bloc.state.value.estimate)
        assertEquals(Activity.UNKNOWN, f.bloc.state.value.activity)
    }

    @Test fun explicitStillAnchorsLatestEstimateNotOlderThrottledPoint() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix()
        runCurrent()
        f.clock.now = 2.0
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.00009, 30.0, 5.0, 2000, 2.0,
            LocationSource.gps, 0.0, 0.0)))
        runCurrent()
        val latest = assertNotNull(f.bloc.state.value.estimate)
        assertTrue(latest.latitude > 50.0)
        assertEquals(1, f.queue.size(session.identity))
        f.bloc.dispatch(TrackerEvent.ActivityChanged(Activity.STILL))
        f.clock.now = 3.0
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        assertEquals(latest.latitude, assertNotNull(f.bloc.state.value.estimate).latitude, 1e-5)
        assertTrue(assertNotNull(f.bloc.state.value.estimate).latitude > 50.0)
    }

    @Test fun shutdownCancelsNetworkAndRejectsLateCallbacks() = runTest {
        val f = Fixture(backgroundScope)
        val pending = CompletableDeferred<Unit>()
        var cancelled = false
        f.transport.beforeResponse = {
            try { pending.await() } finally { cancelled = true }
        }
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix()
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        f.bloc.shutdown()
        assertTrue(cancelled)
        assertFalse(f.bloc.state.value.running)
        assertFailsWith<IllegalStateException> { f.bloc.dispatch(TrackerEvent.Tick) }
        assertEquals(1, f.queue.size(session.identity))
    }

    @Test fun identityRequiresAllConfirmedShiftFields() {
        assertFailsWith<IllegalArgumentException> { TrackingSession.fromConfirmedShift("s", "", "brand") }
        assertFailsWith<IllegalArgumentException> { TrackingSession.fromConfirmedShift("", "driver", "brand") }
    }

    @Test fun quietImuCannotReviveAnExpiredFixAsAStationaryAnchor() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix()
        runCurrent()
        f.clock.now = 18.0
        repeat(10) { i ->
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(17.82 + i * 0.02, null, null, 0.01)))
        }
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        assertNull(f.bloc.state.value.estimate)
        assertContains(f.bloc.state.value.flags, "positionStale")
    }

    @Test fun staleMotionCannotCountTowardStationaryDetection() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.clock.now = 5.0
        repeat(10) { i ->
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(1.0 + i * 0.02, null, null, 0.01)))
        }
        f.fix()
        runCurrent()
        assertEquals(LocationSource.gps, f.bloc.state.value.estimate?.source)
    }

    @Test fun barometerVetoUsesMeasuredRateAndPreventsExplicitStill() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix()
        f.bloc.dispatch(TrackerEvent.Motion(MotionSample(1.0, null, null, 0.01, pressureHpa = 1000.0)))
        runCurrent()
        f.clock.now = 2.0
        f.bloc.dispatch(TrackerEvent.Motion(MotionSample(2.0, null, null, 0.01, pressureHpa = 1000.03)))
        f.bloc.dispatch(TrackerEvent.ActivityChanged(Activity.STILL))
        runCurrent()
        assertEquals(Activity.UNKNOWN, f.bloc.state.value.activity)
        f.clock.now = 5.0
        f.bloc.dispatch(TrackerEvent.ActivityChanged(Activity.STILL))
        runCurrent()
        assertEquals(Activity.STILL, f.bloc.state.value.activity)
    }
}
