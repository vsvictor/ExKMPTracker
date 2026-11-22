package com.exkmptracker.model

import kotlinx.serialization.Serializable

@Serializable
enum class LocationSource { fused, gps, network, deadReckoning, stationary, cooperative }

enum class Activity { UNKNOWN, STILL, WALKING, IN_VEHICLE }

@Serializable
data class TrackingIdentity @Throws(IllegalArgumentException::class) constructor(val driverId: String, val brandId: String) {
    init {
        require(driverId.isNotBlank() && brandId.isNotBlank()) { "Confirmed shift identity is required" }
    }
}

@Serializable
data class TrackingSession @Throws(IllegalArgumentException::class) constructor(val sessionId: String, val identity: TrackingIdentity) {
    init { require(sessionId.isNotBlank()) { "Authenticated sessionId is required" } }

    companion object {
        @Throws(IllegalArgumentException::class)
        fun fromConfirmedShift(sessionId: String, performerId: String, brandId: String) =
            TrackingSession(sessionId, TrackingIdentity(performerId, brandId))
    }
}

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val observedAtMs: Long,
    val monotonicSeconds: Double,
    val source: LocationSource,
    val speedMps: Double = -1.0,
    val bearingDegrees: Double = -1.0,
    val speedAccuracyMps: Double? = null,
    val bearingAccuracyDegrees: Double? = null,
    val isMock: Boolean = false,
)

data class TrackEstimate(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val speedMps: Double,
    val bearingDegrees: Double,
    val monotonicSeconds: Double,
    val source: LocationSource,
)

@Serializable
data class TrackPoint(
    val id: String,
    val rawLatitude: Double?,
    val rawLongitude: Double?,
    val rawAccuracyMeters: Double?,
    val smoothedLatitude: Double,
    val smoothedLongitude: Double,
    val smoothedAccuracyMeters: Double,
    val speedMps: Double?,
    val bearingDegrees: Double?,
    val deviceTimeMs: Long,
    val observedAtMs: Long,
    val source: LocationSource,
    val isMock: Boolean,
    val flags: List<String>,
)

data class MotionSample(
    val monotonicSeconds: Double,
    val longitudinalAcceleration: Double?,
    val yawRateRadians: Double?,
    val linearAccelerationMagnitude: Double,
    val pressureHpa: Double? = null,
    val compassDegrees: Double? = null,
)

data class GnssEvidence(
    val monotonicSeconds: Double,
    val utcTimeMs: Double?,
    val uncertaintyMs: Double = 0.0,
    val meanAgcDb: Double? = null,
    val meanCn0DbHz: Double? = null,
    /** Mean C/N0 of satellites used in the fix. Null when the receiver did not report them. */
    val strongCn0DbHz: Double? = null,
)
