package com.exkmptracker.android

import kotlin.math.sqrt

internal data class AccelerationVector(val x: Double, val y: Double, val z: Double) {
    constructor(values: FloatArray) : this(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
}

internal data class StationaryWake(
    val gravity: AccelerationVector? = null,
    val magnitude: Double = 0.0,
    val candidates: Int = 0,
) {
    val shouldWake: Boolean get() = candidates >= 3
}

internal fun sampleStationaryWake(previous: StationaryWake, acceleration: AccelerationVector): StationaryWake {
    val gravity = previous.gravity ?: return StationaryWake(gravity = acceleration)
    val dx = acceleration.x - gravity.x
    val dy = acceleration.y - gravity.y
    val dz = acceleration.z - gravity.z
    val magnitude = sqrt(dx * dx + dy * dy + dz * dz)
    // Measure against the baseline before adapting it, and never adapt to movement.
    val nextGravity = if (magnitude < 0.2) {
        AccelerationVector(gravity.x + 0.2 * dx, gravity.y + 0.2 * dy, gravity.z + 0.2 * dz)
    } else {
        gravity
    }
    return StationaryWake(nextGravity, magnitude, if (magnitude > 0.8) previous.candidates + 1 else 0)
}
