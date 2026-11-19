package com.exkmptracker.cooperative

import kotlinx.serialization.Serializable

@Serializable
data class CooperativeState(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val speedMps: Double,
    val bearingDegrees: Double,
    val trustScore: Double,
    val vehicleClass: VehicleClass,
    val flags: List<String>,
)
