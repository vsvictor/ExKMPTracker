package com.exkmptracker.bloc

import com.exkmptracker.model.LocationSource

data class TrackerStatistics(
    val acquisitions: Map<LocationSource, Long> = emptyMap(),
    val rejections: Map<String, Long> = emptyMap(),
    val enqueuedPoints: Long = 0,
    val trackMeters: Double = 0.0,
    val stationaryTrackMeters: Double = 0.0,
    val stationaryDriftMetersBySource: Map<LocationSource, Double> = emptyMap(),
    val deadReckoningErrorMeters: Double = 0.0,
    val deadReckoningRecoveries: Long = 0,
    val serverClockOffsetMs: Double? = null,
    val gnssClockOffsetMs: Double? = null,
    val gnssMeanCn0DbHz: Double? = null,
    val gnssUsedCn0DbHz: Double? = null,
)
