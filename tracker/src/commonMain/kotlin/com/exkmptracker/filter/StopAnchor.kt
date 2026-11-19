package com.exkmptracker.filter

import com.exkmptracker.model.*
import kotlin.math.*

internal class StopAnchor {
    private var anchor: TrackEstimate? = null
    private var began = 0.0
    private var fromNetwork = false
    private var refined = false
    private var count = 0
    private var totalWeight = 0.0
    private var weightedLatitude = 0.0
    private var weightedLongitudeOffset = 0.0
    private var longitudeReference = 0.0
    private var bestAccuracy = Double.POSITIVE_INFINITY

    val active: Boolean get() = anchor != null

    fun driftMeters(fix: LocationFix): Double? =
        anchor?.let { distanceMeters(it.latitude, it.longitude, fix.latitude, fix.longitude) }

    fun begin(estimate: TrackEstimate, network: Boolean = false) {
        if (active) return
        anchor = estimate.copy(speedMps = 0.0, source = LocationSource.stationary)
        began = estimate.monotonicSeconds
        fromNetwork = network
        longitudeReference = estimate.longitude
    }

    fun shouldRelease(fix: LocationFix): Boolean {
        val held = anchor ?: return false
        if (fix.source == LocationSource.network) return false
        return fix.speedMps > 1.5 || (fix.accuracyMeters <= 20.0 &&
            distanceMeters(held.latitude, held.longitude, fix.latitude, fix.longitude) > max(50.0, 3 * fix.accuracyMeters))
    }

    fun add(fix: LocationFix) {
        val held = anchor ?: return
        if (fix.source == LocationSource.network || fix.accuracyMeters > 20.0) return
        if (fromNetwork) {
            anchor = held.copy(latitude = fix.latitude, longitude = fix.longitude, accuracyMeters = fix.accuracyMeters)
            fromNetwork = false
        }
        if (refined) return
        val weight = 1.0 / fix.accuracyMeters.pow(2)
        totalWeight += weight
        weightedLatitude += fix.latitude * weight
        weightedLongitudeOffset += wrapRadians((fix.longitude - longitudeReference) * RADIANS) / RADIANS * weight
        bestAccuracy = min(bestAccuracy, fix.accuracyMeters)
        count++
        val accuracy = max(sqrt(1 / totalWeight), bestAccuracy / 4)
        val current = anchor ?: return
        if (count >= 10 && accuracy <= current.accuracyMeters / 2) {
            anchor = current.copy(latitude = weightedLatitude / totalWeight,
                longitude = wrapRadians((longitudeReference + weightedLongitudeOffset / totalWeight) * RADIANS) / RADIANS,
                accuracyMeters = accuracy)
            refined = true
        }
    }

    fun estimate(now: Double): TrackEstimate? =
        anchor?.let { it.copy(monotonicSeconds = now, accuracyMeters = it.accuracyMeters + max(0.0, now - began) * 0.1) }
}
