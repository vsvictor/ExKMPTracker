package com.exkmptracker.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationRequestSpecTest {
    @Test
    fun firstFixIsNotHeldForAccuracyAndCacheStaysFreshEnoughToAccept() {
        val moving = androidLocationRequestSpec(stationary = false)
        val still = androidLocationRequestSpec(stationary = true)
        assertFalse(moving.waitForAccurateLocation)
        assertFalse(still.waitForAccurateLocation)
        assertEquals(CACHED_FIX_MAX_AGE_MS, moving.maxUpdateAgeMs)
        assertEquals(CACHED_FIX_MAX_AGE_MS, still.maxUpdateAgeMs)
        assertTrue(moving.maxUpdateAgeMs in 1..9_000)
        assertEquals(1_000L, moving.intervalMs)
        assertEquals(5_000L, still.intervalMs)
    }
}
