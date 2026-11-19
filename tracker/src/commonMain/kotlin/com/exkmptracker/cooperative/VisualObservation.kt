package com.exkmptracker.cooperative

import kotlinx.serialization.Serializable

@Serializable
data class VisualObservation(
    val observationId: String,
    val vehicleClass: VehicleClass,
    val distanceMeters: Double,
    /** Calibrated absolute true-north azimuth, not a device-relative camera angle. */
    val azimuthDegrees: Double,
    val relativeSpeedMps: Double?,
    /** Conservative per-axis variance including range, angle and camera calibration error. */
    val visionVarianceMeters2: Double,
) {
    fun validate() {
        require(observationId.isNotBlank() && observationId.length <= 64)
        require(distanceMeters.isFinite() && distanceMeters in 0.1..200.0)
        require(azimuthDegrees.isFinite() && azimuthDegrees in 0.0..<360.0)
        require(relativeSpeedMps == null || relativeSpeedMps.isFinite() && relativeSpeedMps in -122.3..122.3)
        require(visionVarianceMeters2.isFinite() && visionVarianceMeters2 in 0.01..10_000.0)
    }
}
