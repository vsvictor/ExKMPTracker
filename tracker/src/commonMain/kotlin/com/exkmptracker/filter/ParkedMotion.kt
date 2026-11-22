package com.exkmptracker.filter

import com.exkmptracker.model.LocationFix
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max

/**
 * Separates parked GNSS noise from slow travel.
 * An oscillating blob pins the published coordinate at the window origin.
 * A straight or curving ground track is left to the filter, so real motion
 * keeps its accuracy. The filter itself is always updated by the bloc.
 */
internal class ParkedMotion {
    private data class Sample(
        val time: Double,
        val latitude: Double,
        val longitude: Double,
        val speed: Double,
        val accuracy: Double,
    )

    private val samples = ArrayDeque<Sample>()
    private var departureSamples = 0

    fun clear() {
        samples.clear()
        departureSamples = 0
    }

    fun hasSamples(): Boolean = samples.isNotEmpty()

    /**
     * True once the open window is reversing on itself.
     * The bloc then republishes the coordinate captured at the first sample.
     */
    fun pinned(): Boolean = oscillating() && contained()

    /** True when a precise fix has stayed in a non-travelling blob for the whole window. */
    fun observe(fix: LocationFix): Boolean {
        if (!fix.isPreciseFix()) return false
        val previous = samples.lastOrNull()
        if (previous != null && fix.monotonicSeconds < previous.time) clear()
        if (samples.isEmpty() && fix.speedMps >= DRIVING_SPEED_MPS) return false
        val origin = samples.firstOrNull()
        val candidate = Sample(fix.monotonicSeconds, fix.latitude, fix.longitude, fix.speedMps, fix.accuracyMeters)
        if (origin != null && distance(origin, candidate) > gate(fix.accuracyMeters)) {
            if (leaving(origin, candidate)) {
                departureSamples++
                if (departureSamples >= DEPARTURE_SAMPLES) clear()
            }
            samples.addLast(candidate)
            val follow = consistentTravel() || realManeuver()
            samples.removeLast()
            if (follow || departureSamples >= DEPARTURE_SAMPLES) clear()
            return false
        }
        departureSamples = 0
        samples.addLast(candidate)
        val newest = fix.monotonicSeconds
        while (samples.size > 1 && newest - samples.first().time > WINDOW_SECONDS) samples.removeFirst()
        if (consistentTravel() || realManeuver()) {
            clear()
            return false
        }
        val oldest = samples.first()
        if (newest - oldest.time < WINDOW_SECONDS) return false
        return contained()
    }

    private fun contained(): Boolean {
        val oldest = samples.firstOrNull() ?: return false
        val gate = gate(samples.last().accuracy)
        return samples.all { distance(oldest, it) <= gate }
    }

    /** Net movement in one direction. A parked cloud does not do this. */
    private fun consistentTravel(): Boolean {
        if (samples.size < 2) return false
        val net = distance(samples.first(), samples.last())
        val path = pathLength()
        return net >= TRAVEL_NET_METERS && path > 0.0 && net / path > TRAVEL_RATIO
    }

    /**
     * A curve or a turn still has a long ground track.
     * Oscillation is excluded: its path is long only because it reverses.
     */
    private fun realManeuver(): Boolean {
        if (samples.size < 2 || oscillating()) return false
        val dt = samples.last().time - samples.first().time
        if (dt < 1.0) return false
        val path = pathLength()
        return path >= MANEUVER_PATH_METERS && path / dt >= MANEUVER_SPEED_MPS
    }

    private fun oscillating(): Boolean {
        var reversals = 0
        var steps = 0
        for (i in 2 until samples.size) {
            val prev = distance(samples[i - 2], samples[i - 1])
            val step = distance(samples[i - 1], samples[i])
            if (prev < STEP_METERS || step < STEP_METERS) continue
            steps++
            val turn = abs(wrapRadians(bearing(samples[i - 2], samples[i - 1]) - bearing(samples[i - 1], samples[i])))
            if (turn > REVERSAL_RADIANS) reversals++
        }
        return steps >= 1 && reversals * 2 >= steps
    }

    private fun leaving(origin: Sample, fix: Sample) =
        fix.speed > RELEASE_SPEED_MPS &&
            distance(origin, fix) > max(RELEASE_DISTANCE_METERS, 2.5 * fix.accuracy)

    private fun pathLength(): Double {
        var path = 0.0
        for (i in 1 until samples.size) path += distance(samples[i - 1], samples[i])
        return path
    }

    private fun distance(a: Sample, b: Sample) =
        distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    private fun bearing(a: Sample, b: Sample): Double {
        val north = (b.latitude - a.latitude) * RADIANS * EARTH_RADIUS_METERS
        val east = wrapRadians((b.longitude - a.longitude) * RADIANS) *
            EARTH_RADIUS_METERS * cos(a.latitude * RADIANS)
        return atan2(east, north)
    }

    private fun gate(accuracy: Double) = max(BLOB_METERS, accuracy)

    private companion object {
        const val WINDOW_SECONDS = 15.0
        const val BLOB_METERS = 25.0
        const val TRAVEL_NET_METERS = 8.0
        const val TRAVEL_RATIO = 0.65
        const val MANEUVER_PATH_METERS = 12.0
        const val MANEUVER_SPEED_MPS = 1.0
        const val STEP_METERS = 1.0
        const val REVERSAL_RADIANS = 2.0943951023931953
        const val DRIVING_SPEED_MPS = 3.0
        const val RELEASE_SPEED_MPS = 1.0
        const val RELEASE_DISTANCE_METERS = 20.0
        const val DEPARTURE_SAMPLES = 2
    }
}
