package com.exkmptracker.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StationaryWakeTest {
    private val gravity = AccelerationVector(0.0, 0.0, 9.81)

    @Test
    fun sustainedGentleHorizontalAccelerationWakesAfterThreeSamples() {
        val gentleStart = AccelerationVector(1.0, 0.0, 9.81)
        var state = StationaryWake(gravity)
        repeat(3) { index ->
            state = sampleStationaryWake(state, gentleStart)
            assertEquals(gravity, state.gravity)
            assertEquals(1.0, state.magnitude)
            assertEquals(index + 1, state.candidates)
            assertEquals(index == 2, state.shouldWake)
        }
        assertTrue(state.shouldWake)
    }

    @Test
    fun quietNoiseAdaptsGravityWithoutWaking() {
        var state = StationaryWake(gravity)
        repeat(10) { index ->
            val noise = if (index % 2 == 0) 0.1 else -0.1
            state = sampleStationaryWake(state, AccelerationVector(noise, 0.0, 9.81))
            assertEquals(0, state.candidates)
            assertFalse(state.shouldWake)
        }
        assertTrue(state.gravity != gravity)
    }
}
