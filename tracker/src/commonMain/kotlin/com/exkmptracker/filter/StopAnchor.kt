package com.exkmptracker.filter

import com.exkmptracker.model.*
import kotlin.math.*

internal class StopAnchor {
    private var anchor: TrackEstimate? = null
    private var transitionStart: TrackEstimate? = null
    private var transitionStartedAt = 0.0
    // Keep departure detection independent of refinements to the displayed position.
    private var departureAnchor: TrackEstimate? = null
    private var began = 0.0
    private var departureSamples = 0

    val active: Boolean get() = anchor != null

    fun driftMeters(fix: LocationFix): Double? =
        anchor?.let { distanceMeters(it.latitude, it.longitude, fix.latitude, fix.longitude) }

    fun begin(estimate: TrackEstimate) {
        if (active) return
        anchor = estimate.copy(speedMps = 0.0, source = LocationSource.stationary)
        transitionStart = anchor
        transitionStartedAt = estimate.monotonicSeconds
        departureAnchor = anchor
        began = estimate.monotonicSeconds
        departureSamples = 0
    }

    /**
     * A parked Doppler spike or one multipath jump is not a departure.
     * Two consecutive non-network fixes must both be faster than 1 m/s and outside
     * the hold radius before the anchor moves and mileage starts again.
     */
    fun shouldRelease(fix: LocationFix): Boolean {
        val held = departureAnchor ?: return false
        if (fix.source == LocationSource.network) return false
        val moved = distanceMeters(held.latitude, held.longitude, fix.latitude, fix.longitude)
        val leaving = fix.speedMps > RELEASE_SPEED_MPS &&
            moved > max(RELEASE_DISTANCE_METERS, 2.5 * fix.accuracyMeters)
        departureSamples = if (leaving) departureSamples + 1 else 0
        return departureSamples >= DEPARTURE_SAMPLES
    }

    fun add(estimate: TrackEstimate, now: Double = estimate.monotonicSeconds) {
        val held = anchor ?: return
        if (estimate.accuracyMeters >= held.accuracyMeters) return
        transitionStart = position(now, held)
        transitionStartedAt = now
        anchor = estimate.copy(speedMps = 0.0, source = LocationSource.stationary)
        began = now
    }

    /**
     * Uncertainty grows only while precise fixes are absent.
     * [corroboratedAt] is the time of the last precise fix; null means the anchor start.
     */
    fun estimate(now: Double, corroboratedAt: Double? = null): TrackEstimate? =
        anchor?.let {
            val since = corroboratedAt?.takeIf { time -> time.isFinite() }?.let { time -> max(began, time) } ?: began
            val growth = min(ACCURACY_GROWTH_CAP_METERS, max(0.0, now - since) * 0.1)
            val current = position(now, it)
            val remaining = distanceMeters(current.latitude, current.longitude, it.latitude, it.longitude)
            current.copy(monotonicSeconds = now, accuracyMeters = it.accuracyMeters + remaining + growth)
        }

    private fun position(now: Double, target: TrackEstimate): TrackEstimate {
        val start = transitionStart ?: return target
        val distance = distanceMeters(start.latitude, start.longitude, target.latitude, target.longitude)
        val travelled = max(0.0, now - transitionStartedAt) * TRANSITION_SPEED_MPS
        if (distance <= travelled || distance < 1e-6) return target
        val latitude = start.latitude * RADIANS
        val targetLatitude = target.latitude * RADIANS
        val longitudeDelta = wrapRadians((target.longitude - start.longitude) * RADIANS)
        val bearing = atan2(sin(longitudeDelta) * cos(targetLatitude),
            cos(latitude) * sin(targetLatitude) - sin(latitude) * cos(targetLatitude) * cos(longitudeDelta))
        val arc = travelled / EARTH_RADIUS_METERS
        val resultLatitude = asin((sin(latitude) * cos(arc) + cos(latitude) * sin(arc) * cos(bearing)).coerceIn(-1.0, 1.0))
        val longitudeOffset = atan2(sin(bearing) * sin(arc) * cos(latitude),
            cos(arc) - sin(latitude) * sin(resultLatitude))
        return target.copy(latitude = resultLatitude / RADIANS,
            longitude = wrapRadians(start.longitude * RADIANS + longitudeOffset) / RADIANS)
    }

    private companion object {
        const val RELEASE_SPEED_MPS = 1.0
        const val RELEASE_DISTANCE_METERS = 20.0
        const val DEPARTURE_SAMPLES = 2
        const val ACCURACY_GROWTH_CAP_METERS = 15.0
        const val TRANSITION_SPEED_MPS = 0.2
    }
}
