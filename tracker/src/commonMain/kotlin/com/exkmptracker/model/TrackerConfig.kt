package com.exkmptracker.model

data class TrackerConfig @Throws(IllegalArgumentException::class) constructor(
    val antiFake: Boolean = true,
    val maxGnssAccuracyMeters: Double = 20.0,
    val maxNetworkAccuracyMeters: Double = 50.0,
    val maxEstimateAccuracyMeters: Double = 100.0,
    val predictionHorizonSeconds: Double = 15.0,
    val recordDistanceMeters: Double = 25.0,
    val recordBearingDegrees: Double = 10.0,
    val recordIntervalSeconds: Double = 30.0,
) {
    init {
        require(listOf(maxGnssAccuracyMeters, maxNetworkAccuracyMeters, maxEstimateAccuracyMeters,
            predictionHorizonSeconds, recordDistanceMeters, recordBearingDegrees,
            recordIntervalSeconds).all { it.isFinite() && it > 0 })
        require(predictionHorizonSeconds <= 15.0) { "Dead reckoning must not exceed 15 seconds" }
    }
}
