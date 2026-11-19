package com.exkmptracker.ios

import com.exkmptracker.bloc.TrackerState
import com.exkmptracker.model.LocationSource
import com.exkmptracker.ports.TrackerClock
import platform.Foundation.NSLock

/**
 * Course reference for projecting IMU acceleration onto the vehicle track. It is fed only from
 * the BLoC's accepted [TrackerState.estimate], never from raw CoreLocation callbacks, so mock,
 * stale, poor or rejected fixes cannot calibrate the motion frame indirectly.
 *
 * A moving gps/fused estimate with a valid bearing becomes the anchor and keeps its accepted
 * observation time. Dead-reckoned estimates may provide the current estimated heading only while
 * that anchor is within [MAX_ANCHOR_AGE_SECONDS]. Otherwise no trustworthy frame exists and the
 * caller must withhold IMU samples.
 */
internal class IosAcceptedCourseReference(private val clock: TrackerClock) {
    private val lock = NSLock()
    private var anchorMonotonicSeconds: Double? = null
    private var currentBearingDegrees: Double? = null

    fun onState(state: TrackerState) {
        val estimate = if (state.sections.stage1) state.autonomousEstimate ?: state.estimate else null
        lock.lock()
        try {
            if (!state.running || estimate == null) {
                clearLocked()
                return
            }
            val bearing = estimate.bearingDegrees.takeIf { it.isFinite() && it >= 0.0 && it < 360.0 }
            when (estimate.source) {
                LocationSource.gps, LocationSource.fused -> {
                    val previous = anchorMonotonicSeconds
                    if (bearing != null && estimate.speedMps.isFinite() &&
                        estimate.speedMps >= MIN_COURSE_SPEED_MPS &&
                        estimate.monotonicSeconds.isFinite() &&
                        (previous == null || estimate.monotonicSeconds >= previous)
                    ) {
                        anchorMonotonicSeconds = estimate.monotonicSeconds
                        currentBearingDegrees = bearing
                    }
                }
                LocationSource.deadReckoning -> {
                    if (bearing != null && anchorMonotonicSeconds != null) currentBearingDegrees = bearing
                }
                LocationSource.network, LocationSource.stationary, LocationSource.cooperative -> Unit
            }
        } finally {
            lock.unlock()
        }
    }

    /** Returns a true-north bearing only while the accepted gps/fused anchor is fresh. */
    fun trustedBearingDegrees(): Double? {
        val now = clock.monotonicSeconds()
        lock.lock()
        try {
            val anchor = anchorMonotonicSeconds ?: return null
            val age = now - anchor
            if (!age.isFinite() || age < 0.0 || age > MAX_ANCHOR_AGE_SECONDS) {
                clearLocked()
                return null
            }
            return currentBearingDegrees
        } finally {
            lock.unlock()
        }
    }

    fun clear() {
        lock.lock()
        try {
            clearLocked()
        } finally {
            lock.unlock()
        }
    }

    private fun clearLocked() {
        anchorMonotonicSeconds = null
        currentBearingDegrees = null
    }

    private companion object {
        const val MAX_ANCHOR_AGE_SECONDS = 15.0
        const val MIN_COURSE_SPEED_MPS = 1.0
    }
}
