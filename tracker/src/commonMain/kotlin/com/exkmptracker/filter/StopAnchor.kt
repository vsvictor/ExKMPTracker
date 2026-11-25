package com.exkmptracker.filter

import com.exkmptracker.model.*
import kotlin.math.*

internal class StopAnchor {
    private var anchor: TrackEstimate? = null
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

    fun add(estimate: TrackEstimate) {
        val held = anchor ?: return
        if (estimate.accuracyMeters >= held.accuracyMeters) return
        anchor = estimate.copy(speedMps = 0.0, source = LocationSource.stationary)
        began = estimate.monotonicSeconds
    }

    /**
     * Uncertainty grows only while precise fixes are absent.
     * [corroboratedAt] is the time of the last precise fix; null means the anchor start.
     */
    fun estimate(now: Double, corroboratedAt: Double? = null): TrackEstimate? =
        anchor?.let {
            val since = corroboratedAt?.takeIf { time -> time.isFinite() }?.let { time -> max(began, time) } ?: began
            val growth = min(ACCURACY_GROWTH_CAP_METERS, max(0.0, now - since) * 0.1)
            it.copy(monotonicSeconds = now, accuracyMeters = it.accuracyMeters + growth)
        }

    private companion object {
        const val RELEASE_SPEED_MPS = 1.0
        const val RELEASE_DISTANCE_METERS = 20.0
        const val DEPARTURE_SAMPLES = 2
        const val ACCURACY_GROWTH_CAP_METERS = 15.0
    }
}
