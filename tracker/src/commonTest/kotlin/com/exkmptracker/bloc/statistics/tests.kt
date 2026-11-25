package com.exkmptracker.bloc.statistics

import com.exkmptracker.bloc.TrackerBloc
import com.exkmptracker.bloc.TrackerEvent
import com.exkmptracker.model.*
import com.exkmptracker.ports.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerStatisticsTests {
    @Test
    fun recordsAcquisitionsRejectionsAndQueuedDistances() = runTest {
        var now = 100.0
        var pointId = 0
        val points = mutableListOf<TrackPoint>()
        val bloc = TrackerBloc(
            backgroundScope,
            object : TrackerClock {
                override fun wallTimeMs() = (now * 1000).toLong()
                override fun monotonicSeconds() = now
            },
            object : TrackQueue {
                override suspend fun append(identity: TrackingIdentity, point: TrackPoint) { points.add(point) }
                override suspend fun peek(identity: TrackingIdentity, limit: Int) = points.take(limit)
                override suspend fun acknowledge(identity: TrackingIdentity, ids: List<String>) { repeat(ids.size) { points.removeAt(0) } }
                override suspend fun size(identity: TrackingIdentity) = points.size.toLong()
            },
            object : TrackingTransport {
                override suspend fun serverTime(session: TrackingSession) = ServerTime(100_000)
                override suspend fun ingest(session: TrackingSession, points: List<TrackPoint>) = points.map { it.id }
            },
            object : PointIdGenerator { override fun newId() = (++pointId).toString(16).padStart(32, '0') },
            object : TrackerLogger { override fun error(message: String, cause: Throwable?) = Unit },
            object : SensorController { override fun setStationary(stationary: Boolean) = Unit },
        )
        val session = TrackingSession.fromConfirmedShift("session", "driver", "brand")
        bloc.dispatch(TrackerEvent.Start(session))
        bloc.dispatch(TrackerEvent.Position(LocationFix(50.0, 30.0, 5.0, 100_000, now, LocationSource.gps, 0.0, 0.0)))
        bloc.dispatch(TrackerEvent.Position(LocationFix(50.0, 30.0, 5.0, 1_000, 1.0, LocationSource.fused, 0.0, 0.0)))
        bloc.dispatch(TrackerEvent.Position(LocationFix(50.0, 30.0, 10.0, 100_000, now, LocationSource.network, 0.0, 0.0)))
        runCurrent()
        val stats = bloc.state.value.statistics
        assertEquals(1L, stats.acquisitions[LocationSource.gps])
        assertEquals(1L, stats.acquisitions[LocationSource.fused])
        assertEquals(1L, stats.acquisitions[LocationSource.network])
        assertEquals(2L, stats.rejections.values.sum())
        assertEquals(1L, stats.enqueuedPoints)
        assertEquals(0.0, stats.trackMeters)
        assertEquals(0.0, stats.stationaryTrackMeters)
        assertNull(stats.serverClockOffsetMs)

        bloc.dispatch(TrackerEvent.Gnss(GnssEvidence(now, 99_500.0, meanCn0DbHz = 30.0, strongCn0DbHz = 35.0)))
        bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        assertEquals(500.0, bloc.state.value.statistics.gnssClockOffsetMs)
        assertEquals(30.0, bloc.state.value.statistics.gnssMeanCn0DbHz)
        now += 6
        bloc.dispatch(TrackerEvent.Tick)
        runCurrent()
        assertNull(bloc.state.value.statistics.gnssClockOffsetMs)
        assertNull(bloc.state.value.statistics.gnssMeanCn0DbHz)
        assertTrue(bloc.state.value.statistics.serverClockOffsetMs != null)
    }
}
