package com.exkmptracker.filter.imu

import com.exkmptracker.filter.TrackingFilter
import com.exkmptracker.model.LocationFix
import com.exkmptracker.model.LocationSource
import kotlin.math.*
import kotlin.test.*

class ImuHistoryTests {
    private fun filter() = TrackingFilter().also {
        assertNotNull(it.fix(LocationFix(50.0, 30.0, 5.0, 1000, 1.0,
            LocationSource.gps, speedMps = 0.0, bearingDegrees = 0.0)))
    }

    @Test fun integratesActualFiftyHzHistoryNotOnlyLastSample() {
        val filter = filter()
        repeat(100) {
            val time = 1.0 + it * 0.02
            filter.observeMotion(time, if (it < 50) 2.0 else 0.0, 0.0)
        }
        val result = assertNotNull(filter.predict(3.0, 0.0, 0.0))
        assertEquals(2.0, result.speedMps, 0.001)
        val distance = (result.latitude - 50.0) * 111_194.9266
        assertTrue(distance in 2.9..3.1, "Integrated distance was $distance")
    }

    @Test fun staleControlIsNotExtrapolatedAcrossEntireTunnel() {
        val filter = filter()
        filter.observeMotion(1.0, 2.0, 0.0)
        val result = assertNotNull(filter.predict(5.0, 6.0, 3.0))
        assertEquals(2.0, result.speedMps, 0.001)
        assertEquals(0.0, result.bearingDegrees, 0.001)
    }

    @Test fun unavailableControlsAndRejectedFixDoNotDestroyHistory() {
        val filter = filter()
        filter.observeMotion(1.0, null, null)
        filter.observeMotion(2.0, 1.0, 0.0)
        assertNull(filter.fix(LocationFix(51.0, 31.0, 5.0, 2500, 2.5,
            LocationSource.gps, speedMps = 0.0, bearingDegrees = 0.0)))
        val result = assertNotNull(filter.predict(3.0, 0.0, 0.0))
        assertEquals(1.0, result.speedMps, 0.001)
    }

    @Test fun fiftyHzTurnFollowsAnalyticArcWithinFifteenCentimetres() {
        val filter = TrackingFilter()
        assertNotNull(filter.fix(LocationFix(50.0, 30.0, 5.0, 1000, 1.0,
            LocationSource.gps, speedMps = 10.0, bearingDegrees = 0.0)))
        repeat(500) { filter.observeMotion(1.0 + it * 0.02, 0.0, 0.1) }
        val result = assertNotNull(filter.predict(11.0, 0.0, 0.0))
        val radians = PI / 180
        val east = (result.longitude - 30.0) * radians * 6_371_000 * cos(50.0 * radians)
        val north = (result.latitude - 50.0) * radians * 6_371_000
        val error = hypot(east - 100 * (1 - cos(1.0)), north - 100 * sin(1.0))
        assertTrue(error < 0.15, "Numerical arc error was $error m")
        assertEquals(180 / PI, result.bearingDegrees, 0.001)
    }
}
