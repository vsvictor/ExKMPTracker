package com.exkmptracker.filter

import kotlin.math.*

internal const val EARTH_RADIUS_METERS = 6_371_000.0
internal const val RADIANS = PI / 180.0

internal fun wrapRadians(value: Double): Double {
    val wrapped = (value + PI) % (2 * PI)
    return (if (wrapped <= 0) wrapped + 2 * PI else wrapped) - PI
}

internal fun bearingDegrees(radians: Double): Double = (radians / RADIANS + 360.0) % 360.0

internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1) * RADIANS
    val dLon = wrapRadians((lon2 - lon1) * RADIANS)
    val a = sin(dLat / 2).pow(2) + cos(lat1 * RADIANS) * cos(lat2 * RADIANS) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
