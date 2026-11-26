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
        val batches = mutableListOf<List<TrackPoint>>()
        override suspend fun serverTime(session: TrackingSession) = ServerTime(1000)
        override suspend fun ingest(session: TrackingSession, points: List<TrackPoint>): List<String> {
            calls++
            batches.add(points.toList())
            beforeResponse?.invoke()
            return accepted(points)
        }
    }

    private class Fixture(scope: CoroutineScope, config: TrackerConfig = TrackerConfig()) {
        val clock = Clock()
        val queue = Queue()
        val transport = Transport()
        val logged = mutableListOf<String>()
        var nextId = 0
        val bloc = TrackerBloc(scope, clock, queue, transport,
            object : PointIdGenerator { override fun newId() = (++nextId).toString(16).padStart(32, '0') },
            object : TrackerLogger { override fun error(message: String, cause: Throwable?) { logged.add(message) } },
            object : SensorController { override fun setStationary(stationary: Boolean) {} },
            config = config)
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
        assertEquals(0.0, f.bloc.state.value.statistics.trackMeters)
        assertEquals(0.0, f.bloc.state.value.statistics.stationaryTrackMeters)
        assertNotNull(f.bloc.state.value.error)
        f.queue.failAppend = false
        f.clock.now = 2.0
        f.fix()
        runCurrent()
        assertEquals(1, f.queue.size(session.identity))
        assertEquals(0.0, f.queue.peek(session.identity, 1).single().trackIncrementMeters)
    }

    @Test fun acknowledgesOnlyAcceptedFifoPrefix() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.fix()
        runCurrent()
        f.clock.now = 121.0
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.002, 30.0, 5.0, 121_000, 121.0,
            LocationSource.gps, 8.0, 0.0)))
        runCurrent()
        f.clock.now = 241.0
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.004, 30.0, 5.0, 241_000, 241.0,
            LocationSource.gps, 8.0, 0.0)))
        runCurrent()
        val original = f.queue.peek(session.identity, 100)
        assertEquals(3, original.size)
        assertEquals(0.0, original.first().trackIncrementMeters)
        original.zipWithNext { previous, point ->
            val distance = com.exkmptracker.filter.distanceMeters(previous.smoothedLatitude, previous.smoothedLongitude,
                point.smoothedLatitude, point.smoothedLongitude)
            assertEquals(distance, assertNotNull(point.trackIncrementMeters), 1e-9)
            assertEquals(0.0, point.phantomIncrementMeters)
        }
        assertEquals(original.sumOf { assertNotNull(it.trackIncrementMeters) }, f.bloc.state.value.statistics.trackMeters, 1e-9)
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

    @Test fun stationaryPointsAreRecordedAndUploadedEveryThirtySeconds() = runTest {
        assertStationaryCadence(TrackerConfig(), 30)
    }

    @Test fun stationaryPointsAreRecordedAndUploadedEveryFiveSeconds() = runTest {
        assertStationaryCadence(TrackerConfig(stationaryIntervalSeconds = 5.0), 5)
    }

    private suspend fun TestScope.assertStationaryCadence(config: TrackerConfig, interval: Int) {
        val f = Fixture(backgroundScope, config)
        f.bloc.dispatch(TrackerEvent.Start(session))
        repeat(10) { i ->
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(i / 10.0, 0.0, 0.0, 0.01)))
        }
        f.fix()
        runCurrent()
        assertEquals(LocationSource.stationary, f.bloc.state.value.estimate?.source)

        for (second in 1..1 + interval * 3) {
            f.clock.now = second.toDouble()
            f.bloc.dispatch(TrackerEvent.Tick)
            runCurrent()
        }

        val points = f.transport.batches.flatten().filter { it.source == LocationSource.stationary }
        assertEquals((0..3).map { (1L + it * interval) * 1_000L }, points.map { it.deviceTimeMs })
        assertTrue(points.all { it.rawLatitude == 50.0 && it.rawLongitude == 30.0 && it.rawAccuracyMeters == 5.0 })
        assertTrue(points.all { it.observedAtMs == 1_000L })
        assertEquals(4, f.transport.batches.size)
        assertTrue(f.transport.batches.all { it.size == 1 })
        assertEquals(0, f.queue.size(session.identity))
    }

    @Test fun stationaryHeartbeatUsesImprovedFilteredPosition() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        repeat(10) { i ->
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(i / 10.0, 0.0, 0.0, 0.01)))
        }
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.0, 30.0, 20.0, 1000, 1.0,
            LocationSource.gps, 0.0, 0.0)))
        runCurrent()
        val initial = assertNotNull(f.bloc.state.value.estimate)
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()

        f.clock.now = 2.0
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.00001, 30.0, 4.0, 2000, 2.0,
            LocationSource.gps, 0.0, 0.0)))
        runCurrent()
        val improved = assertNotNull(f.bloc.state.value.estimate)
        assertEquals(initial.latitude, improved.latitude, 1e-9)

        f.clock.now = 31.0
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        val heartbeat = f.transport.batches.flatten().last()
        assertEquals(LocationSource.stationary, heartbeat.source)
        assertNotEquals(improved.latitude, heartbeat.smoothedLatitude)
        assertEquals(50.00001, heartbeat.rawLatitude)
        assertEquals(30.0, heartbeat.rawLongitude)
        assertEquals(4.0, heartbeat.rawAccuracyMeters)
        assertEquals(2000L, heartbeat.observedAtMs)
        assertEquals(31_000L, heartbeat.deviceTimeMs)
        assertEquals(0, f.queue.size(session.identity))
    }

    @Test fun stationaryUploadedCoordinatesMoveAtMostOneMeterPerFiveSeconds() = runTest {
        val f = Fixture(backgroundScope, TrackerConfig(stationaryIntervalSeconds = 5.0))
        f.bloc.dispatch(TrackerEvent.Start(session))
        repeat(10) { i ->
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(i / 10.0, 0.0, 0.0, 0.01)))
        }
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.0, 30.0, 20.0, 1_000L, 1.0,
            LocationSource.gps, 0.0, 0.0)))
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        f.clock.now = 2.0
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.00009, 30.0, 4.0, 2_000L, 2.0,
            LocationSource.gps, 0.0, 0.0)))
        runCurrent()
        for (second in listOf(6.0, 11.0, 16.0)) {
            f.clock.now = second
            f.bloc.dispatch(TrackerEvent.Tick)
            runCurrent()
        }
        val points = f.transport.batches.flatten().filter { it.source == LocationSource.stationary }
        assertEquals(4, points.size)
        val distances = points.zipWithNext { a, b ->
            com.exkmptracker.filter.distanceMeters(a.smoothedLatitude, a.smoothedLongitude,
                b.smoothedLatitude, b.smoothedLongitude)
        }
        assertTrue(distances.any { it > 0.1 })
        assertTrue(distances.all { it <= 1.000001 })
        points.drop(1).zip(distances).forEach { (point, distance) ->
            assertEquals(distance, assertNotNull(point.trackIncrementMeters), 1e-9)
            assertEquals(distance, assertNotNull(point.phantomIncrementMeters), 1e-9)
        }
        assertTrue(points.drop(1).all { it.rawLatitude == 50.00009 && it.observedAtMs == 2_000L })
    }

    @Test fun stationaryRecordingUsesLatestAcceptedRawFixAndPreservesObservationTime() = runTest {
        val f = Fixture(backgroundScope, TrackerConfig(stationaryIntervalSeconds = 5.0))
        f.bloc.dispatch(TrackerEvent.Start(session))
        repeat(10) { i ->
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(i / 10.0, 0.0, 0.0, 0.01)))
        }
        f.fix()
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()

        f.clock.now = 5.0
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.00001, 30.0, 4.0, 4_500L, 5.0,
            LocationSource.gps, 0.0, 0.0)))
        runCurrent()
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.00002, 30.0, 150.0, 5_000L, 5.0,
            LocationSource.gps, 0.0, 0.0)))
        runCurrent()
        f.clock.now = 6.0
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        f.clock.now = 11.0
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()

        val points = f.transport.batches.flatten()
        assertEquals(3, points.size)
        for (point in points.drop(1)) {
            assertEquals(50.00001, point.rawLatitude)
            assertEquals(30.0, point.rawLongitude)
            assertEquals(4.0, point.rawAccuracyMeters)
            assertEquals(4_500L, point.observedAtMs)
            assertTrue(point.deviceTimeMs > point.observedAtMs)
        }
    }

    @Test fun stationaryPositionEventRecordsItsRawFixWhenIntervalIsDue() = runTest {
        val f = Fixture(backgroundScope, TrackerConfig(stationaryIntervalSeconds = 5.0))
        f.bloc.dispatch(TrackerEvent.Start(session))
        repeat(10) { i ->
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(i / 10.0, 0.0, 0.0, 0.01)))
        }
        f.fix()
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        f.clock.now = 6.0
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.00001, 30.0, 4.0, 5_500L, 6.0,
            LocationSource.gps, 0.0, 0.0)))
        f.bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        val point = f.transport.batches.flatten().last()
        assertEquals(LocationSource.stationary, point.source)
        assertEquals(50.00001, point.rawLatitude)
        assertEquals(4.0, point.rawAccuracyMeters)
        assertEquals(5_500L, point.observedAtMs)
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

    @Test fun oneSlowSampleAfterDrivingDoesNotAnchor() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.0, 30.0, 5.0, 1000, 1.0,
            LocationSource.gps, 12.0, 0.0, speedAccuracyMps = 0.1)))
        runCurrent()
        val continued = 50.0 + 12.0 * 3.2 / 6_371_000.0 * 180.0 / kotlin.math.PI
        f.clock.now = 4.2
        repeat(10) { i ->
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(3.3 + i / 10.0, 0.0, 0.0, 0.01)))
        }
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(continued, 30.0, 5.0, 4200, 4.2,
            LocationSource.gps, 0.0, 0.0, speedAccuracyMps = 0.1)))
        runCurrent()
        assertEquals(LocationSource.gps, f.bloc.state.value.estimate?.source)
        f.clock.now = 6.0
        repeat(10) { i ->
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(5.1 + i / 10.0, 0.0, 0.0, 0.01)))
        }
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(continued, 30.0, 5.0, 6000, 6.0,
            LocationSource.gps, 0.1, 0.0, speedAccuracyMps = 0.1)))
        runCurrent()
        assertEquals(LocationSource.stationary, f.bloc.state.value.estimate?.source)
    }

    @Test fun stillFollowsAMovingFixInsteadOfDroppingIt() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        repeat(10) { i ->
            f.bloc.dispatch(TrackerEvent.Motion(MotionSample(i / 10.0, 0.0, 0.0, 0.01)))
        }
        f.fix()
        f.bloc.dispatch(TrackerEvent.ActivityChanged(Activity.STILL))
        runCurrent()
        f.clock.now = 3.0
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.00011, 30.0, 8.0, 3000, 3.0,
            LocationSource.gps, 2.0, 0.0)))
        runCurrent()
        f.clock.now = 4.0
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.00022, 30.0, 8.0, 4000, 4.0,
            LocationSource.gps, 2.0, 0.0)))
        runCurrent()
        assertEquals(Activity.STILL, f.bloc.state.value.activity)
        assertEquals(LocationSource.stationary, f.bloc.state.value.estimate?.source)
        f.clock.now = 5.0
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.00033, 30.0, 8.0, 5000, 5.0,
            LocationSource.gps, 2.0, 0.0)))
        runCurrent()
        assertEquals(Activity.UNKNOWN, f.bloc.state.value.activity)
        // Posterior lags the raw 12 m steps. 0.00015° is about 17 m clear of the anchor.
        assertTrue(assertNotNull(f.bloc.state.value.estimate).latitude > 50.00015)
        assertEquals(LocationSource.gps, f.bloc.state.value.estimate?.source)
    }

    @Test fun parkedDopplerNoiseDoesNotExtendTheTrack() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        for (second in 0..16) {
            f.clock.now = 1.0 + second
            val lat = 50.0 + (if (second % 2 == 0) 3.0 else -3.0) / 111_320.0
            f.bloc.dispatch(TrackerEvent.Position(LocationFix(lat, 30.0, 5.0,
                (f.clock.now * 1000).toLong(), f.clock.now, LocationSource.gps, 1.6, (second * 40 % 360).toDouble())))
            runCurrent()
        }
        val parkedEstimate = assertNotNull(f.bloc.state.value.estimate)
        assertEquals(LocationSource.stationary, parkedEstimate.source)
        val parked = f.queue.points.getValue(session.identity)
        assertTrue(parked.all { kotlin.math.abs(it.smoothedLatitude - 50.0) < 25.0 / 111_320.0 })
        f.clock.now = 18.0
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.00003, 30.0, 5.0, 18_000, 18.0,
            LocationSource.gps, 1.6, 40.0)))
        runCurrent()
        val refined = assertNotNull(f.bloc.state.value.estimate)
        assertTrue(com.exkmptracker.filter.distanceMeters(parkedEstimate.latitude, parkedEstimate.longitude,
            refined.latitude, refined.longitude) <= 0.200001)
        val before = f.queue.size(session.identity)
        f.clock.now = 20.0
        f.bloc.dispatch(TrackerEvent.Position(LocationFix(50.0, 30.0, 5.0, 20_000, 20.0,
            LocationSource.gps, 2.5, 90.0)))
        runCurrent()
        assertEquals(LocationSource.stationary, f.bloc.state.value.estimate?.source)
        assertEquals(before, f.queue.size(session.identity))
    }

    @Test fun straightCreepKeepsTheFilterPosition() = runTest {
        val f = Fixture(backgroundScope)
        f.bloc.dispatch(TrackerEvent.Start(session))
        for (second in 0..12) {
            f.clock.now = 1.0 + second
            val lat = 50.0 + second * 1.5 / 111_320.0
            f.bloc.dispatch(TrackerEvent.Position(LocationFix(lat, 30.0, 5.0,
                (f.clock.now * 1000).toLong(), f.clock.now, LocationSource.gps, 1.5, 0.0)))
            runCurrent()
        }
        val estimated = assertNotNull(f.bloc.state.value.estimate)
        assertEquals(LocationSource.gps, estimated.source)
        assertTrue(estimated.latitude > 50.0 + 8.0 / 111_320.0)
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
