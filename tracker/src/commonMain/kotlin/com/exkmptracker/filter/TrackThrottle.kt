package com.exkmptracker.filter

import com.exkmptracker.model.LocationSource
import com.exkmptracker.model.TrackEstimate
import com.exkmptracker.model.TrackerConfig
import kotlin.math.abs
import kotlin.math.max

/**
 * Records fewer points while the vehicle is slow and more while it is fast.
 *
 * [TrackerConfig.recordDistanceMeters], [TrackerConfig.recordBearingDegrees] and
 * [TrackerConfig.recordIntervalSeconds] are the gates at [REFERENCE_SPEED_MPS].
 * The scale is `reference / max(speed, 1 m/s)`, clamped to 0.4..4, so speeds of
 * 2 m/s and below share the sparsest schedule and speeds of 20 m/s and above share
 * the densest one. Each gate is then clamped to a physical range.
 */
class TrackThrottle(private val config: TrackerConfig = TrackerConfig()) {
    private var previous: TrackEstimate? = null

    fun shouldRecord(estimate: TrackEstimate): Boolean {
        val last = previous ?: return true
        if (departure(last, estimate) || speedStep(last, estimate) ||
            sourceRecovered(last, estimate) || becameStationary(last, estimate) ||
            accuracyShifted(last, estimate)) return true
        val gates = gates(estimate.speedMps)
        val turned = estimate.speedMps >= turnSpeedFloor(estimate.speedMps) &&
            bearingDeltaDegrees(last.bearingDegrees, estimate.bearingDegrees) >= gates.bearingDegrees
        return distanceMeters(last.latitude, last.longitude, estimate.latitude, estimate.longitude) >=
            gates.distanceMeters || turned ||
            estimate.monotonicSeconds - last.monotonicSeconds >= gates.intervalSeconds
    }

    fun recorded(estimate: TrackEstimate) { previous = estimate }

    /** How long the bloc waits before the next ingest attempt. Empty queues do not call ingest. */
    fun flushIntervalSeconds(speedMps: Double): Double =
        (REFERENCE_FLUSH_SECONDS * scale(speedMps)).coerceIn(MIN_FLUSH_SECONDS, MAX_FLUSH_SECONDS)

    private fun gates(speedMps: Double): Gates {
        val factor = scale(speedMps)
        return Gates(
            distanceMeters = (config.recordDistanceMeters * factor)
                .coerceIn(MIN_DISTANCE_METERS, MAX_DISTANCE_METERS),
            bearingDegrees = (config.recordBearingDegrees * factor)
                .coerceIn(MIN_BEARING_DEGREES, MAX_BEARING_DEGREES),
            intervalSeconds = (config.recordIntervalSeconds * factor)
                .coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS),
        )
    }

    private fun scale(speedMps: Double): Double {
        val speed = speedMps.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        return (REFERENCE_SPEED_MPS / max(speed, SLOW_SPEED_FLOOR_MPS)).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    private fun turnSpeedFloor(speedMps: Double) = if (speedMps < MOVING_SPEED_MPS) SLOW_TURN_SPEED_MPS else MOVING_SPEED_MPS

    /** Smallest course change in degrees. Stays exact at the gate; radian wrapping does not. */
    private fun bearingDeltaDegrees(from: Double, to: Double): Double {
        val delta = (to - from) % 360.0
        val wrapped = when {
            delta > 180.0 -> delta - 360.0
            delta <= -180.0 -> delta + 360.0
            else -> delta
        }
        return abs(wrapped)
    }

    /** A crawl that becomes real motion is sent at once, before the slow distance gate. */
    private fun departure(last: TrackEstimate, next: TrackEstimate) =
        last.speedMps in 0.0..<DEPARTURE_FROM_MPS && next.speedMps >= DEPARTURE_TO_MPS

    /** Braking or acceleration of at least 5 m/s is kept even when the car has not moved far. */
    private fun speedStep(last: TrackEstimate, next: TrackEstimate) =
        last.speedMps >= 0.0 && next.speedMps >= 0.0 && abs(next.speedMps - last.speedMps) >= SHARP_SPEED_CHANGE_MPS

    /** The first precise fix after a gap, a network fallback, or a stop replaces the held point. */
    private fun sourceRecovered(last: TrackEstimate, next: TrackEstimate): Boolean {
        val lost = last.source == LocationSource.deadReckoning || last.source == LocationSource.stationary ||
            last.source == LocationSource.network
        val precise = next.source == LocationSource.gps || next.source == LocationSource.fused
        return lost && precise
    }

    private fun becameStationary(last: TrackEstimate, next: TrackEstimate) =
        last.source != LocationSource.stationary && next.source == LocationSource.stationary

    private fun accuracyShifted(last: TrackEstimate, next: TrackEstimate): Boolean {
        val worse = next.accuracyMeters - last.accuracyMeters >= ACCURACY_WORSE_METERS
        val better = last.accuracyMeters - next.accuracyMeters >= ACCURACY_BETTER_METERS &&
            next.accuracyMeters <= ACCURACY_RECOVERED_METERS
        return worse || better
    }

    private data class Gates(val distanceMeters: Double, val bearingDegrees: Double, val intervalSeconds: Double)

    private companion object {
        const val REFERENCE_SPEED_MPS = 8.0
        const val SLOW_SPEED_FLOOR_MPS = 1.0
        const val MIN_SCALE = 0.4
        const val MAX_SCALE = 4.0
        const val MIN_DISTANCE_METERS = 8.0
        const val MAX_DISTANCE_METERS = 100.0
        const val MIN_BEARING_DEGREES = 6.0
        const val MAX_BEARING_DEGREES = 30.0
        const val MIN_INTERVAL_SECONDS = 5.0
        const val MAX_INTERVAL_SECONDS = 120.0
        const val REFERENCE_FLUSH_SECONDS = 10.0
        const val MIN_FLUSH_SECONDS = 5.0
        const val MAX_FLUSH_SECONDS = 30.0
        const val MOVING_SPEED_MPS = 1.0
        const val SLOW_TURN_SPEED_MPS = 0.5
        const val DEPARTURE_FROM_MPS = 1.0
        const val DEPARTURE_TO_MPS = 2.0
        const val SHARP_SPEED_CHANGE_MPS = 5.0
        const val ACCURACY_WORSE_METERS = 40.0
        const val ACCURACY_BETTER_METERS = 25.0
        const val ACCURACY_RECOVERED_METERS = 25.0
    }
}
