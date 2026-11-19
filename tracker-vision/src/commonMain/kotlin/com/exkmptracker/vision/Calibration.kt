package com.exkmptracker.vision

import kotlin.math.*

/**
 * Intrinsics for upright, undistorted RGBA frames, in pixel-center coordinates.
 * The calibrated local ground plane is horizontal; cameraHeightMeters is its distance below the camera.
 */
data class CameraCalibration(
    val imageWidth: Int,
    val imageHeight: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val cameraHeightMeters: Double,
    val heightStdDevMeters: Double,
    val contactStdDevPixels: Double,
    val maxGroundRangeMeters: Double,
    val minDepressionDegrees: Double = 3.0,
) {
    init {
        require(imageWidth > 0 && imageHeight > 0)
        require(listOf(fx, fy, cameraHeightMeters, heightStdDevMeters, contactStdDevPixels, maxGroundRangeMeters)
            .all { it.isFinite() && it > 0 })
        require(cx.isFinite() && cx in 0.0..imageWidth.toDouble())
        require(cy.isFinite() && cy in 0.0..imageHeight.toDouble())
        require(minDepressionDegrees > 0 && minDepressionDegrees < 90)
    }
}

/**
 * Capture-time camera extrinsics, not raw device heading.
 * Pitch is positive down; positive roll rotates image-right toward level-down.
 */
data class CameraPose(
    val opticalHeadingDegreesTrue: Double,
    val pitchDownDegrees: Double,
    val rollDegrees: Double,
    val headingStdDevDegrees: Double,
    val tiltStdDevDegrees: Double,
) {
    init {
        require(opticalHeadingDegreesTrue >= 0 && opticalHeadingDegreesTrue < 360)
        require(pitchDownDegrees > -90 && pitchDownDegrees < 90)
        require(rollDegrees in -180.0..180.0)
        require(headingStdDevDegrees > 0 && headingStdDevDegrees <= 90)
        require(tiltStdDevDegrees > 0 && tiltStdDevDegrees <= 45)
    }
}

internal data class GroundPoint(val north: Double, val east: Double, val variance: Double) {
    val distance get() = hypot(north, east)
    val azimuth get() = (atan2(east, north) / DEG + 360) % 360
}

private const val DEG = PI / 180

internal fun projectContact(c: CameraCalibration, pose: CameraPose, u: Double, v: Double): GroundPoint? {
    if (!u.isFinite() || !v.isFinite() || u !in 0.0..<c.imageWidth.toDouble() ||
        v !in 0.0..<c.imageHeight.toDouble()) return null
    val x = (u - c.cx) / c.fx
    val y = (v - c.cy) / c.fy
    val roll = pose.rollDegrees * DEG
    val pitch = pose.pitchDownDegrees * DEG
    val right = x * cos(roll) - y * sin(roll)
    val down = x * sin(roll) + y * cos(roll)
    val forward = cos(pitch) - down * sin(pitch)
    val vertical = sin(pitch) + down * cos(pitch)
    val horizontal = hypot(right, forward)
    val depression = atan2(vertical, horizontal)
    val pixelAngle = hypot(c.contactStdDevPixels / c.fx, c.contactStdDevPixels / c.fy)
    val rayAngle = hypot(pixelAngle, pose.tiltStdDevDegrees * DEG)
    if (forward <= 0 || depression <= max(c.minDepressionDegrees * DEG, 2 * rayAngle)) return null
    val scale = c.cameraHeightMeters / vertical
    val range = scale * horizontal
    if (!range.isFinite() || range <= 0 || range > c.maxGroundRangeMeters) return null
    val heading = pose.opticalHeadingDegreesTrue * DEG
    val scaleSigma = range * c.heightStdDevMeters / c.cameraHeightMeters
    val depthSigma = c.cameraHeightMeters * rayAngle / sin(depression).pow(2)
    val lateralSigma = range * hypot(rayAngle, pose.headingStdDevDegrees * DEG)
    val variance = scaleSigma.pow(2) + depthSigma.pow(2) + lateralSigma.pow(2)
    if (!variance.isFinite() || variance <= 0) return null
    return GroundPoint(
        scale * (forward * cos(heading) - right * sin(heading)),
        scale * (forward * sin(heading) + right * cos(heading)),
        variance,
    )
}
