package com.exkmptracker.filter

import com.exkmptracker.model.TrackEstimate
import com.exkmptracker.model.TrackerConfig
import kotlin.math.abs

class TrackThrottle(private val config: TrackerConfig = TrackerConfig()) {
    private var previous: TrackEstimate? = null

    fun shouldRecord(estimate: TrackEstimate): Boolean {
        val last = previous ?: return true
        return distanceMeters(last.latitude, last.longitude, estimate.latitude, estimate.longitude) >= config.recordDistanceMeters ||
            (estimate.speedMps > 1 && abs(wrapRadians((estimate.bearingDegrees - last.bearingDegrees) * RADIANS)) / RADIANS >= config.recordBearingDegrees) ||
            estimate.monotonicSeconds - last.monotonicSeconds >= config.recordIntervalSeconds
    }

    fun recorded(estimate: TrackEstimate) { previous = estimate }
}
