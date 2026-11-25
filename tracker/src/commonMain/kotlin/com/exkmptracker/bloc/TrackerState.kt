package com.exkmptracker.bloc

import com.exkmptracker.model.*

data class TrackerState(
    val sections: TrackerSections = TrackerSections(),
    val autonomousEstimate: TrackEstimate? = null,
    val cooperativeStatus: String? = null,
    val running: Boolean = false,
    val identity: TrackingIdentity? = null,
    val activity: Activity = Activity.UNKNOWN,
    val estimate: TrackEstimate? = null,
    val rejection: String? = null,
    val error: String? = null,
    val queuedPoints: Long = 0,
    val acceptedFixes: Long = 0,
    val rejectedFixes: Long = 0,
    val networkFixes: Long = 0,
    val stationaryDriftSamples: Long = 0,
    val stationaryDriftMeanMeters: Double = 0.0,
    val stationaryDriftMaxMeters: Double = 0.0,
    val lastUploadTimeMs: Long? = null,
    val flags: Set<String> = emptySet(),
    val statistics: TrackerStatistics = TrackerStatistics(),
    val serviceStarts: Long? = null,
    val serviceRestarts: Long? = null,
)
