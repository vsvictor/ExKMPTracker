package com.exkmptracker.filter

import com.exkmptracker.model.*
import com.exkmptracker.cooperative.MatchedDonor
import com.exkmptracker.cooperative.VisualObservation
import kotlin.math.*

/** iOS has no network source, so a 59 m Core Location fix is otherwise treated as GPS. */
internal fun LocationFix.isPreciseFix(): Boolean =
    source != LocationSource.network && accuracyMeters.isFinite() &&
        accuracyMeters <= PRECISE_FIX_ACCURACY_METERS

private const val PRECISE_FIX_ACCURACY_METERS = 25.0

/** At or above this C/N0 the provider radius is used as reported. */
internal const val STRONG_CN0_DB_HZ = 32.0

/** At or below this C/N0 the GNSS/fused sigma is tripled. */
internal const val WEAK_CN0_DB_HZ = 20.0

/** Fewer satellites than this cannot confirm a healthy solution. */
internal const val MIN_CN0_SATELLITES = 4

/**
 * Mean C/N0 of satellites used in the fix. An empty list means the receiver did not
 * report a solution, so the provider radius stays unchanged. One to three satellites
 * are capped at the weak end even when those few are loud.
 */
internal fun strongCn0FromUsed(usedCn0DbHz: List<Double>): Double? {
    val used = usedCn0DbHz.filter { it.isFinite() && it > 0.0 }
    if (used.isEmpty()) return null
    val mean = used.average()
    return if (used.size >= MIN_CN0_SATELLITES) mean else mean.coerceAtMost(WEAK_CN0_DB_HZ)
}

/** 1 at [STRONG_CN0_DB_HZ], 3 at [WEAK_CN0_DB_HZ], linear between, saturated outside. */
internal fun weakCn0SigmaScale(strongCn0DbHz: Double?): Double {
    val cn0 = strongCn0DbHz?.takeIf { it.isFinite() } ?: return 1.0
    val deficit = ((STRONG_CN0_DB_HZ - cn0) / (STRONG_CN0_DB_HZ - WEAK_CN0_DB_HZ)).coerceIn(0.0, 1.0)
    return 1.0 + 2.0 * deficit
}

class TrackingFilter(private val config: TrackerConfig = TrackerConfig()) {
    private var x = DoubleArray(4)
    private var p = Array(4) { DoubleArray(4) }
    private var originLatitude = 0.0
    private var originLongitude = 0.0
    private var time = Double.NEGATIVE_INFINITY
    private var lastFix: LocationFix? = null
    private var rejectedLatitude = Double.NaN
    private var rejectedLongitude = Double.NaN
    private var rejectedCluster = 0
    private data class Control(val time: Double, val acceleration: Double?, val yawRate: Double?)
    private val controls = mutableListOf<Control>()
    var rejection: String? = null
        private set

    /** Retain actual IMU history instead of applying the latest sample to a whole GNSS gap. */
    fun observeMotion(time: Double, acceleration: Double?, yawRate: Double?) {
        require(time.isFinite() && acceleration?.isFinite() != false && yawRate?.isFinite() != false)
        if (controls.lastOrNull()?.let { time <= it.time } == true) return
        controls.add(Control(time, acceleration, yawRate))
        val oldest = time - 16.0
        while (controls.size > 2_000 || controls.size > 1 && controls[1].time < oldest) controls.removeAt(0)
    }

    fun fix(fix: LocationFix, acceleration: Double = 0.0, yawRate: Double = 0.0,
            constrainLateral: Boolean = false, strongCn0DbHz: Double? = null): TrackEstimate? {
        rejection = validate(fix)
        if (rejection != null) return null
        val previous = lastFix
        if (previous != null && fix.monotonicSeconds <= time) return reject("Non-monotonic fix")
        if (config.antiFake) {
            if (fix.isMock) return reject("Mock location")
            if (fix.speedMps > MAX_SPEED) return reject("Speed exceeds 220 km/h")
            if (previous != null) {
                val gap = fix.monotonicSeconds - previous.monotonicSeconds
                if (gap > config.predictionHorizonSeconds &&
                    (distanceMeters(previous.latitude, previous.longitude, fix.latitude, fix.longitude) -
                        fix.accuracyMeters - 100.0) / gap > MAX_SPEED
                ) return reject("Implausible position after signal gap")
            }
        }
        val variance = positionVariance(fix, strongCn0DbHz)
        val gap = if (previous == null) Double.POSITIVE_INFINITY else fix.monotonicSeconds - previous.monotonicSeconds
        if (previous == null || gap > config.predictionHorizonSeconds) {
            // A 50–65 m Core Location sample is still labelled gps on iOS. It must not
            // wipe a recent precise lock just because the previous fix aged out.
            if (previous != null && !fix.isPreciseFix() && previous.isPreciseFix() && gap <= COARSE_REINIT_SECONDS) {
                return reject("Coarse fix cannot replace a recent precise position")
            }
            resetTo(fix, variance)
        } else {
            val savedX = x.copyOf()
            val savedP = Array(4) { p[it].copyOf() }
            val savedTime = time
            val savedOriginLatitude = originLatitude
            val savedOriginLongitude = originLongitude
            advance(fix.monotonicSeconds, acceleration, yawRate, constrainLateral)
            val east = wrapRadians((fix.longitude - originLongitude) * RADIANS) *
                EARTH_RADIUS_METERS * cos(originLatitude * RADIANS)
            val north = (fix.latitude - originLatitude) * RADIANS * EARTH_RADIUS_METERS
            val de = east - x[0]
            val dn = north - x[1]
            val s00 = p[0][0] + variance
            val s11 = p[1][1] + variance
            val s01 = p[0][1]
            val determinant = s00 * s11 - s01 * s01
            val mahalanobis = (s11 * de * de - 2 * s01 * de * dn + s00 * dn * dn) / determinant
            if (!mahalanobis.isFinite() || determinant <= 0 || mahalanobis > CHI_SQUARE_GATE) {
                x = savedX
                p = savedP
                time = savedTime
                originLatitude = savedOriginLatitude
                originLongitude = savedOriginLongitude
                // One spike stays rejected. Three precise fixes that agree with each other
                // are a new position, not a lock on the previous bias.
                if (fix.isPreciseFix() && noteRejectedCluster(fix)) {
                    resetTo(fix, variance)
                    lastFix = fix
                    rejection = null
                    return estimate(fix.source)
                }
                return reject("Position innovation exceeds chi-square gate")
            }
            clearRejectedCluster()
            update(0, east, variance)
            update(1, north, variance)
            if (fix.source != LocationSource.network && fix.speedMps >= 0) update(2, fix.speedMps, speedVariance(fix))
            if (fix.source != LocationSource.network && fix.bearingDegrees >= 0 && x[2] > MIN_COURSE_UPDATE_SPEED_MPS) {
                update(3, fix.bearingDegrees * RADIANS, headingVariance(fix))
            }
        }
        lastFix = fix
        return estimate(fix.source)
    }

    fun predict(now: Double, acceleration: Double, yawRate: Double,
                constrainLateral: Boolean = false): TrackEstimate? {
        val fix = lastFix ?: return null
        if (!now.isFinite() || now <= time || now - fix.monotonicSeconds > config.predictionHorizonSeconds) return null
        advance(now, acceleration, yawRate, constrainLateral)
        return estimate(LocationSource.deadReckoning).takeIf { it.accuracyMeters <= config.maxEstimateAccuracyMeters }
    }

    fun pedestrianSteps(now: Double, count: Int, compassDegrees: Double): TrackEstimate? {
        val fix = lastFix ?: return null
        if (count !in 1..10 || !compassDegrees.isFinite() || !now.isFinite() ||
            now <= time || now - fix.monotonicSeconds > config.predictionHorizonSeconds) return null
        x[2] = 0.0
        x[3] = wrapRadians(compassDegrees * RADIANS)
        x[0] += 0.75 * count * sin(x[3])
        x[1] += 0.75 * count * cos(x[3])
        p[0][0] += 0.5625 * count
        p[1][1] += 0.5625 * count
        time = now
        return estimate(LocationSource.deadReckoning).takeIf { it.accuracyMeters <= config.maxEstimateAccuracyMeters }
    }

    fun stationary(): TrackEstimate? {
        if (lastFix == null) return null
        update(2, 0.0, 0.01)
        x[2] = 0.0
        return estimate(LocationSource.stationary)
    }

    /** Independent branch: cooperative evidence must never contaminate the autonomous fallback. */
    fun fork(): TrackingFilter = TrackingFilter(config).also { copy ->
        copy.x = x.copyOf()
        copy.p = Array(4) { p[it].copyOf() }
        copy.originLatitude = originLatitude
        copy.originLongitude = originLongitude
        copy.time = time
        copy.lastFix = lastFix
        copy.rejectedLatitude = rejectedLatitude
        copy.rejectedLongitude = rejectedLongitude
        copy.rejectedCluster = rejectedCluster
        copy.controls.addAll(controls)
    }

    fun cooperativeFix(donor: MatchedDonor, observation: VisualObservation,
        captureMonotonicSeconds: Double): TrackEstimate? {
        observation.validate()
        val origin = lastFix ?: return reject("Cooperative update requires an autonomous origin")
        if (!captureMonotonicSeconds.isFinite() || captureMonotonicSeconds < time ||
            captureMonotonicSeconds - origin.monotonicSeconds > config.predictionHorizonSeconds) {
            return reject("Cooperative observation outside filter horizon")
        }
        if (donor.observationId != observation.observationId || donor.vehicleClass != observation.vehicleClass ||
            donor.donorId.isBlank() || donor.donorId.length > 128 ||
            !listOf(donor.latitude, donor.longitude, donor.accuracyMeters, donor.trustScore,
                donor.speedMps, donor.bearingDegrees).all { it.isFinite() } ||
            abs(donor.latitude) > 85.0 || abs(donor.longitude) > 180.0 ||
            donor.accuracyMeters !in 0.1..100.0 || donor.trustScore !in 0.7..1.0 ||
            donor.speedMps !in 0.0..MAX_SPEED || donor.bearingDegrees !in 0.0..<360.0) {
            return reject("Invalid cooperative donor")
        }
        val savedX = x.copyOf()
        val savedP = Array(4) { p[it].copyOf() }
        val savedTime = time
        advance(captureMonotonicSeconds, 0.0, 0.0, true)
        val azimuth = observation.azimuthDegrees * RADIANS
        val north = (donor.latitude - originLatitude) * RADIANS * EARTH_RADIUS_METERS -
            observation.distanceMeters * cos(azimuth)
        val east = wrapRadians((donor.longitude - originLongitude) * RADIANS) *
            EARTH_RADIUS_METERS * cos(originLatitude * RADIANS) -
            observation.distanceMeters * sin(azimuth)
        val variance = donor.accuracyMeters.pow(2) / CHI_68 + observation.visionVarianceMeters2
        val de = east - x[0]
        val dn = north - x[1]
        val s00 = p[0][0] + variance
        val s11 = p[1][1] + variance
        val s01 = p[0][1]
        val det = s00 * s11 - s01 * s01
        val mahalanobis = (s11 * de * de - 2 * s01 * de * dn + s00 * dn * dn) / det
        if (det <= 0 || !mahalanobis.isFinite() || mahalanobis > CHI_SQUARE_GATE) {
            x = savedX
            p = savedP
            time = savedTime
            return reject("Cooperative innovation exceeds chi-square gate")
        }
        update(0, east, variance)
        update(1, north, variance)
        // Consecutive video frames share donor/calibration errors; do not average that bias away.
        p[0][0] = max(p[0][0], min(variance, savedP[0][0]))
        p[1][1] = max(p[1][1], min(variance, savedP[1][1]))
        rejection = null
        return estimate(LocationSource.cooperative).also { corrected ->
            lastFix = origin.copy(latitude = corrected.latitude, longitude = corrected.longitude,
                accuracyMeters = corrected.accuracyMeters, monotonicSeconds = captureMonotonicSeconds,
                source = LocationSource.cooperative)
        }
    }

    private fun advance(target: Double, acceleration: Double, yawRate: Double, lateral: Boolean) {
        var controlIndex = controls.indexOfLast { it.time <= time }
        while (target - time > 1e-8) {
            while (controlIndex + 1 < controls.size && controls[controlIndex + 1].time <= time + 1e-8) {
                controlIndex++
            }
            val observed = controls.getOrNull(controlIndex)?.takeIf { time - it.time < 1.0 }
            val a = (if (controls.isEmpty()) acceleration else observed?.acceleration ?: 0.0)
                .takeIf { it.isFinite() }?.coerceIn(-6.0, 6.0) ?: 0.0
            val w = (if (controls.isEmpty()) yawRate else observed?.yawRate ?: 0.0)
                .takeIf { it.isFinite() }?.coerceIn(-3.0, 3.0) ?: 0.0
            val nextControlTime = controls.getOrNull(controlIndex + 1)?.time ?: target
            val expiry = observed?.let { it.time + 1.0 } ?: target
            val dt = minOf(0.1, target - time, nextControlTime - time, expiry - time)
            if (dt <= 1e-8) {
                time = minOf(target, time + 1e-8)
                continue
            }
            val v = x[2]
            val s = sin(x[3])
            val c = cos(x[3])
            val f = Array(4) { i -> DoubleArray(4) { j -> if (i == j) 1.0 else 0.0 } }
            f[0][2] = s * dt
            f[0][3] = v * c * dt
            f[1][2] = c * dt
            f[1][3] = -v * s * dt
            x[0] += v * s * dt
            x[1] += v * c * dt
            x[2] = max(0.0, v + a * dt)
            x[3] = wrapRadians(x[3] + w * dt)
            val fp = multiply(f, p)
            p = Array(4) { i -> DoubleArray(4) { j -> (0..3).sumOf { k -> fp[i][k] * f[j][k] } } }
            val crossNoise = if (lateral) 0.1 else 1.0
            p[0][0] += (s * s + crossNoise * c * c) * dt
            p[1][1] += (c * c + crossNoise * s * s) * dt
            p[0][1] += (1.0 - crossNoise) * s * c * dt
            p[1][0] = p[0][1]
            p[2][2] += dt
            p[3][3] += 0.02 * dt
            time += dt
        }
        time = target
    }

    private fun update(index: Int, measurement: Double, variance: Double) {
        val innovation = p[index][index] + variance
        val gain = DoubleArray(4) { p[it][index] / innovation }
        val residual = if (index == 3) wrapRadians(measurement - x[index]) else measurement - x[index]
        for (i in 0..3) x[i] += gain[i] * residual
        x[2] = max(0.0, x[2])
        x[3] = wrapRadians(x[3])
        val a = Array(4) { i -> DoubleArray(4) { j -> (if (i == j) 1.0 else 0.0) - if (j == index) gain[i] else 0.0 } }
        val ap = multiply(a, p)
        p = Array(4) { i -> DoubleArray(4) { j ->
            (0..3).sumOf { k -> ap[i][k] * a[j][k] } + gain[i] * variance * gain[j]
        } }
        for (i in 0..3) for (j in 0 until i) {
            val symmetric = (p[i][j] + p[j][i]) * 0.5
            p[i][j] = symmetric
            p[j][i] = symmetric
        }
    }

    private fun estimate(source: LocationSource): TrackEstimate {
        recenterTangentPlane()
        val eigen = (p[0][0] + p[1][1] +
            sqrt((p[0][0] - p[1][1]).pow(2) + 4 * p[0][1].pow(2))) / 2
        val latitude = originLatitude + x[1] / EARTH_RADIUS_METERS / RADIANS
        val longitude = originLongitude + x[0] /
            (EARTH_RADIUS_METERS * cos(originLatitude * RADIANS).coerceAtLeast(1e-8)) / RADIANS
        val radius = sqrt(max(0.0, CHI_68 * eigen)).coerceAtLeast(MIN_POSITION_SIGMA_METERS)
        return TrackEstimate(latitude.coerceIn(-90.0, 90.0),
            wrapRadians(longitude * RADIANS) / RADIANS, radius,
            x[2], bearingDegrees(x[3]), time, source)
    }

    private fun validate(f: LocationFix): String? = when {
        f.source !in listOf(LocationSource.fused, LocationSource.gps, LocationSource.network) -> "Invalid fix source"
        !listOf(f.latitude, f.longitude, f.accuracyMeters, f.monotonicSeconds,
            f.speedMps, f.bearingDegrees).all { it.isFinite() } -> "Non-finite fix"
        abs(f.latitude) > 90 || abs(f.longitude) > 180 -> "Invalid coordinates"
        f.accuracyMeters <= 0 -> "Invalid accuracy"
        f.accuracyMeters > if (f.source == LocationSource.network) config.maxNetworkAccuracyMeters else config.maxGnssAccuracyMeters -> "Accuracy exceeds source threshold"
        f.speedMps < 0 && f.speedMps != -1.0 -> "Invalid speed"
        f.bearingDegrees != -1.0 && f.bearingDegrees !in 0.0..<360.0 -> "Invalid bearing"
        else -> null
    }

    private fun resetTo(fix: LocationFix, variance: Double) {
        originLatitude = fix.latitude
        originLongitude = fix.longitude
        val hasDoppler = fix.source != LocationSource.network
        x = doubleArrayOf(0.0, 0.0, if (hasDoppler) max(0.0, fix.speedMps) else 0.0,
            if (!hasDoppler || fix.bearingDegrees < 0) 0.0 else wrapRadians(fix.bearingDegrees * RADIANS))
        p = Array(4) { DoubleArray(4) }
        p[0][0] = variance
        p[1][1] = variance
        p[2][2] = if (!hasDoppler || fix.speedMps < 0) 25.0 else speedVariance(fix)
        p[3][3] = if (!hasDoppler || fix.bearingDegrees < 0) 1.0 else headingVariance(fix)
        time = fix.monotonicSeconds
        clearRejectedCluster()
    }

    /**
     * Provider radii are optimistic. Network error is wider than the reported radius.
     * A weak Android C/N0 widens GNSS and fused sigma only; it does not reclassify the fix.
     */
    private fun positionVariance(fix: LocationFix, strongCn0DbHz: Double? = null): Double {
        val extra = if (fix.source == LocationSource.network) NETWORK_SIGMA_METERS else 0.0
        val reported = hypot(fix.accuracyMeters, extra)
        val scaled = if (fix.source == LocationSource.network) reported
            else reported * weakCn0SigmaScale(strongCn0DbHz)
        val sigma = max(MIN_POSITION_SIGMA_METERS, scaled)
        return sigma * sigma / CHI_68
    }

    private fun noteRejectedCluster(fix: LocationFix): Boolean {
        val agrees = !rejectedLatitude.isNaN() &&
            distanceMeters(rejectedLatitude, rejectedLongitude, fix.latitude, fix.longitude) <=
                max(CLUSTER_RADIUS_METERS, 2.0 * fix.accuracyMeters)
        rejectedCluster = if (agrees) rejectedCluster + 1 else 1
        rejectedLatitude = fix.latitude
        rejectedLongitude = fix.longitude
        return rejectedCluster >= CLUSTER_COUNT
    }

    private fun clearRejectedCluster() {
        rejectedCluster = 0
        rejectedLatitude = Double.NaN
        rejectedLongitude = Double.NaN
    }

    /** Keep the motion model in a local tangent plane. The conversion is invertible. */
    private fun recenterTangentPlane() {
        val east = x[0]
        val north = x[1]
        if (!east.isFinite() || !north.isFinite() || hypot(east, north) < TANGENT_RECENTER_METERS) return
        val latitude = originLatitude + north / EARTH_RADIUS_METERS / RADIANS
        val longitude = originLongitude + east /
            (EARTH_RADIUS_METERS * cos(originLatitude * RADIANS).coerceAtLeast(1e-8)) / RADIANS
        originLatitude = latitude.coerceIn(-90.0, 90.0)
        originLongitude = wrapRadians(longitude * RADIANS) / RADIANS
        x[0] = 0.0
        x[1] = 0.0
    }

    private fun speedVariance(f: LocationFix) =
        f.speedAccuracyMps?.takeIf { it.isFinite() && it >= 0 }?.let { max(it * it, 0.01) } ?: 4.0

    private fun headingVariance(f: LocationFix) =
        f.bearingAccuracyDegrees?.takeIf { it.isFinite() && it in 0.0..<180.0 }
            ?.let { max((it * RADIANS).pow(2), 0.0003) } ?: 0.1

    private fun reject(reason: String): TrackEstimate? { rejection = reason; return null }

    private fun multiply(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(4) { i -> DoubleArray(4) { j -> (0..3).sumOf { k -> a[i][k] * b[k][j] } } }

    companion object {
        private const val CHI_68 = 2.27886856637673
        private const val CHI_SQUARE_GATE = 13.8155
        private const val MAX_SPEED = 220.0 / 3.6
        private const val MIN_POSITION_SIGMA_METERS = 3.0
        private const val COARSE_REINIT_SECONDS = 60.0
        private const val NETWORK_SIGMA_METERS = 30.0
        private const val MIN_COURSE_UPDATE_SPEED_MPS = 0.5
        private const val CLUSTER_RADIUS_METERS = 40.0
        private const val CLUSTER_COUNT = 3
        private const val TANGENT_RECENTER_METERS = 2_000.0
    }
}
