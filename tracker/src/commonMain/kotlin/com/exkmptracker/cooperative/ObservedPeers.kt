package com.exkmptracker.cooperative

data class ObservedPeers(
    val captureMonotonicSeconds: Double,
    val observations: List<VisualObservation>,
)
