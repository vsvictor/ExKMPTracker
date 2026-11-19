@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.exkmptracker.ios

import com.exkmptracker.bloc.TrackerEvent
import com.exkmptracker.model.Activity
import com.exkmptracker.model.MotionSample
import com.exkmptracker.ports.SensorController
import com.exkmptracker.ports.TrackerClock
import com.exkmptracker.ports.TrackerLogger
import platform.CoreMotion.CMAccelerometerData
import platform.CoreMotion.CMAltimeter
import platform.CoreMotion.CMAltitudeData
import platform.CoreMotion.CMDeviceMotion
import platform.CoreMotion.CMMotionActivity
import platform.CoreMotion.CMMotionActivityManager
import platform.CoreMotion.CMMotionManager
import platform.CoreMotion.CMPedometerData
import platform.CoreMotion.CMPedometer
import platform.CoreMotion.CMAttitudeReferenceFrame
import platform.CoreMotion.CMAttitudeReferenceFrameXMagneticNorthZVertical
import platform.CoreMotion.CMAttitudeReferenceFrameXTrueNorthZVertical
import platform.Foundation.NSDate
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlinx.cinterop.useContents
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CoreMotion adapter. `userAcceleration` is gravity-free and is converted from g to m/s².
 * Attitude rotates acceleration and rotation rate into CoreMotion's reference frame.
 */
class IosSensorController internal constructor(
    private val dispatch: (TrackerEvent) -> Unit,
    private val logger: TrackerLogger,
    private val clock: TrackerClock,
    private val trackBearingDegrees: () -> Double?,
    private val magneticDeclinationDegrees: () -> Double?,
    private val compassHeading: () -> IosHeadingObservation?,
) : SensorController {
    private val motionManager = CMMotionManager()
    private val activityManager = CMMotionActivityManager()
    private val pedometer = CMPedometer()
    private val altimeter = CMAltimeter()
    private val reported = mutableSetOf<String>()
    private val attitudeReferenceFrame: CMAttitudeReferenceFrame? = chooseNorthReferenceFrame()
    private var stationary = false
    private var lastContinuousUptimeOffset: Double? = null
    private var minimumFreshMotionUptime = -Double.MAX_VALUE
    private var continuousSensorsActive = false
    private var trackingActive = false
    private var activityUpdatesActive = false
    private var altimeterActive = false

    /** Device-frame gravity (g) from the last device-motion sample, kept for the wake detector. */
    private var lastDeviceGravity: Vector3? = null
    private var lastDeviceGravityUptime = -Double.MAX_VALUE
    private var wakeGravity: Vector3? = null

    /** Latest unconsumed barometer reading (hPa) and its tracker-clock time. */
    private var pendingPressureHpa: Double? = null
    private var pendingPressureTime = 0.0

    val capabilities: Set<String> = buildSet {
        if (motionManager.isDeviceMotionAvailable() && attitudeReferenceFrame != null) {
            add(if (attitudeReferenceFrame == CMAttitudeReferenceFrameXTrueNorthZVertical) {
                "device-motion-true-north"
            } else {
                "device-motion-magnetic-north"
            })
        }
        if (motionManager.isAccelerometerAvailable()) add("accelerometer-wake")
        if (CMMotionActivityManager.isActivityAvailable()) add("activity-recognition")
        if (CMPedometer.isStepCountingAvailable()) add("step-counting")
        if (CMAltimeter.isRelativeAltitudeAvailable()) add("barometer")
    }

    init {
        motionManager.deviceMotionUpdateInterval = 0.02
        motionManager.accelerometerUpdateInterval = 0.5
    }

    override fun setStationary(stationary: Boolean) {
        dispatch_async(dispatch_get_main_queue()) {
            setStationaryOnMain(stationary)
        }
    }

    internal fun startTrackingOnMain() {
        stationary = false
        trackingActive = true
        motionManager.stopAccelerometerUpdates()
        startContinuousSensors()
        startActivityUpdates()
        startAltimeter()
    }

    internal fun stopTrackingOnMain() {
        stationary = false
        trackingActive = false
        motionManager.stopAccelerometerUpdates()
        stopContinuousSensors()
        if (activityUpdatesActive) activityManager.stopActivityUpdates()
        activityUpdatesActive = false
        if (altimeterActive) altimeter.stopRelativeAltitudeUpdates()
        altimeterActive = false
        pendingPressureHpa = null
        wakeGravity = null
        lastDeviceGravity = null
    }

    private fun setStationaryOnMain(value: Boolean) {
        if (stationary == value) return
        stationary = value
        if (value) {
            // Only CoreMotion is reduced here. CoreLocation keeps running on STILL: on iOS
            // motion callbacks alone do not reliably resume a suspended app.
            // Activity recognition and the barometer stay on (low power) so genuine walking or
            // vehicle transitions and pressure changes are still observed while STILL.
            stopContinuousSensors()
            startWakeAccelerometer()
        } else {
            motionManager.stopAccelerometerUpdates()
            startContinuousSensors()
        }
    }

    private fun startWakeAccelerometer() {
        if (!motionManager.isAccelerometerAvailable()) {
            reportOnce("wake-unavailable", "iOS accelerometer is unavailable; stationary wake sensing is disabled")
            return
        }
        val uptimeNow = NSProcessInfo.processInfo.systemUptime
        wakeGravity = lastDeviceGravity?.takeIf {
            uptimeNow - lastDeviceGravityUptime in 0.0..MAX_WAKE_GRAVITY_AGE_SECONDS
        }
        if (wakeGravity == null) {
            reportOnce("wake-gravity-unavailable", "No recent device-motion gravity vector; the stationary wake reference is initialised from the first accelerometer sample")
        }
        motionManager.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { sample, error ->
            if (error != null) {
                reportOnce("wake-error", "CoreMotion accelerometer wake failed: ${error.localizedDescription}")
                return@startAccelerometerUpdatesToQueue
            }
            val data = sample ?: return@startAccelerometerUpdatesToQueue
            evaluateWake(data)
        }
    }

    private fun evaluateWake(sample: CMAccelerometerData) {
        if (!stationary) return
        val observedAt = freshMonotonicTime(sample.timestamp) ?: return
        val acceleration = sample.acceleration.useContents { Vector3(x, y, z) }
        if (!acceleration.x.isFinite() || !acceleration.y.isFinite() || !acceleration.z.isFinite()) return
        // Raw accelerometer includes gravity. Subtracting the retained device-frame gravity
        // *vector* (not just its norm) keeps horizontal vehicle starts visible: 2 m/s² forward
        // gives ≈2 m/s² here, while |‖a‖ − g| would give only ≈0.2 m/s².
        val gravity = wakeGravity ?: acceleration.also { wakeGravity = it }
        val dx = acceleration.x - gravity.x
        val dy = acceleration.y - gravity.y
        val dz = acceleration.z - gravity.z
        val magnitude = sqrt(dx * dx + dy * dy + dz * dz) * STANDARD_GRAVITY
        if (magnitude < WAKE_QUIET_METERS_PER_SECOND) {
            // Only confirmed-quiet samples refine the reference (slow drift / tilt settling), so it
            // never chases a real sustained acceleration.
            wakeGravity = Vector3(
                gravity.x + WAKE_GRAVITY_ALPHA * dx,
                gravity.y + WAKE_GRAVITY_ALPHA * dy,
                gravity.z + WAKE_GRAVITY_ALPHA * dz,
            )
        }
        // No orientation is measured here, so navigation controls stay null.
        // The shared core counts three consecutive samples above 0.8 m/s² and calls
        // setStationary(false), which restarts continuous CoreMotion here.
        dispatch(
            TrackerEvent.Motion(
                MotionSample(
                    monotonicSeconds = observedAt,
                    longitudinalAcceleration = null,
                    yawRateRadians = null,
                    linearAccelerationMagnitude = magnitude,
                    pressureHpa = takePressure(observedAt),
                    compassDegrees = freshCompass(observedAt),
                ),
            ),
        )
    }

    /**
     * Converts a CoreMotion uptime timestamp (sleep-excluding) into the tracker monotonic domain
     * by its observed age; returns null for stale, future or pre-resume samples.
     */
    private fun freshMonotonicTime(sampleUptime: Double): Double? {
        val uptimeNow = NSProcessInfo.processInfo.systemUptime
        val continuousNow = clock.monotonicSeconds()
        val continuousUptimeOffset = continuousNow - uptimeNow
        val previousOffset = lastContinuousUptimeOffset
        if (previousOffset != null &&
            continuousUptimeOffset - previousOffset > RESUME_GAP_THRESHOLD_SECONDS
        ) {
            minimumFreshMotionUptime = uptimeNow
        }
        lastContinuousUptimeOffset = continuousUptimeOffset
        val sampleAge = uptimeNow - sampleUptime
        if (!sampleAge.isFinite() || sampleAge < 0.0 ||
            sampleAge > MAX_MOTION_SAMPLE_AGE_SECONDS ||
            sampleUptime < minimumFreshMotionUptime
        ) return null
        return continuousNow - sampleAge
    }

    private fun startContinuousSensors() {
        continuousSensorsActive = true
        if (motionManager.isDeviceMotionAvailable() && attitudeReferenceFrame != null) {
            motionManager.startDeviceMotionUpdatesUsingReferenceFrame(
                attitudeReferenceFrame,
                NSOperationQueue.mainQueue,
            ) { motion, error ->
                if (error != null) {
                    reportOnce("device-motion-error", "CoreMotion device-motion updates failed: ${error.localizedDescription}")
                return@startDeviceMotionUpdatesUsingReferenceFrame
                }
                motion?.let(::sendMotion)
            }
        } else {
            reportOnce(
                "device-motion-unavailable",
                "iOS device-motion or a north-referenced vertical attitude frame is unavailable",
            )
        }

        if (CMPedometer.isStepCountingAvailable()) {
            pedometer.startPedometerUpdatesFromDate(NSDate()) { data, error ->
                if (error != null) {
                    reportOnce("pedometer-error", "CoreMotion step updates failed: ${error.localizedDescription}")
                    return@startPedometerUpdatesFromDate
                }
                data?.let(::sendSteps)
            }
        } else {
            reportOnce("steps-unavailable", "iOS step counting is unavailable")
        }
    }

    private fun startActivityUpdates() {
        if (activityUpdatesActive) return
        if (!CMMotionActivityManager.isActivityAvailable()) {
            reportOnce("activity-unavailable", "iOS activity recognition is unavailable")
            return
        }
        activityUpdatesActive = true
        activityManager.startActivityUpdatesToQueue(NSOperationQueue.mainQueue) { activity ->
            activity?.let(::sendActivity)
        }
    }

    private fun startAltimeter() {
        if (altimeterActive) return
        if (!CMAltimeter.isRelativeAltitudeAvailable()) {
            reportOnce("barometer-unavailable", "iOS barometer is unavailable; pressure-based stop veto is disabled")
            return
        }
        altimeterActive = true
        altimeter.startRelativeAltitudeUpdatesToQueue(NSOperationQueue.mainQueue) { data, error ->
            if (error != null) {
                reportOnce("barometer-error", "CoreMotion altimeter updates failed: ${error.localizedDescription}")
                return@startRelativeAltitudeUpdatesToQueue
            }
            data?.let(::onAltitude)
        }
    }

    private fun onAltitude(data: CMAltitudeData) {
        if (!trackingActive) return
        val observedAt = freshMonotonicTime(data.timestamp) ?: return
        // CMAltitudeData.pressure is in kilopascals; 1 kPa = 10 hPa.
        val hectopascals = data.pressure.doubleValue * 10.0
        if (!hectopascals.isFinite() || hectopascals <= 0.0) return
        pendingPressureHpa = hectopascals
        pendingPressureTime = observedAt
    }

    /**
     * Attaches each barometer reading once, to the next motion sample observed within the
     * freshness window, so the core's pressure rate is not diluted by repeated stale values.
     */
    private fun takePressure(sampleTime: Double): Double? {
        val pressure = pendingPressureHpa ?: return null
        if (abs(sampleTime - pendingPressureTime) > MAX_ATTACHED_SENSOR_SKEW_SECONDS) {
            if (sampleTime > pendingPressureTime) pendingPressureHpa = null
            return null
        }
        pendingPressureHpa = null
        return pressure
    }

    /** True-north compass heading observed within the skew window of [sampleTime], else null. */
    private fun freshCompass(sampleTime: Double): Double? {
        val heading = compassHeading() ?: return null
        if (abs(sampleTime - heading.monotonicSeconds) > MAX_ATTACHED_SENSOR_SKEW_SECONDS) return null
        return heading.degrees.takeIf { it.isFinite() && it >= 0.0 && it < 360.0 }
    }

    private fun sendMotion(motion: CMDeviceMotion) {
        val gravityUptime = motion.timestamp
        motion.gravity.useContents { Vector3(x, y, z) }
            .takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
            ?.let {
                lastDeviceGravity = it
                lastDeviceGravityUptime = gravityUptime
            }
        val observedAt = freshMonotonicTime(motion.timestamp) ?: return

        val acceleration = motion.userAcceleration.useContents { Vector3(x, y, z) }
        val rotation = motion.rotationRate.useContents { Vector3(x, y, z) }
        val attitude = motion.attitude.quaternion.useContents { Quaternion(x, y, z, w) }
        val linear = rotate(attitude, acceleration.x, acceleration.y, acceleration.z)
        val angular = rotate(attitude, rotation.x, rotation.y, rotation.z)
        val referenceBearing = longitudinalReferenceBearing()
        val longitudinal = referenceBearing?.let { bearing ->
            val radians = bearing * DEGREES_TO_RADIANS
            val eastAcceleration = -linear.y
            val northAcceleration = linear.x
            (northAcceleration * cos(radians) + eastAcceleration * sin(radians)) * STANDARD_GRAVITY
        }
        // Yaw about world-up needs only the north-referenced vertical attitude and gyroscope,
        // not the vehicle course; it is negated to match clockwise-from-north heading.
        val yawRate = -angular.z
        dispatch(
            TrackerEvent.Motion(
                MotionSample(
                    monotonicSeconds = observedAt,
                    longitudinalAcceleration = longitudinal,
                    yawRateRadians = yawRate.takeIf { it.isFinite() },
                    linearAccelerationMagnitude = sqrt(
                        linear.x * linear.x + linear.y * linear.y + linear.z * linear.z,
                    ) * STANDARD_GRAVITY,
                    pressureHpa = takePressure(observedAt),
                    compassDegrees = freshCompass(observedAt),
                ),
            ),
        )
    }

    /**
     * Bearing of the vehicle's forward axis in the CoreMotion attitude frame, or null (with a
     * diagnostic) when no accepted course or magnetic calibration exists. Without it the
     * longitudinal control is null rather than projected onto an arbitrary phone axis.
     */
    private fun longitudinalReferenceBearing(): Double? {
        val trueBearing = trackBearingDegrees()?.takeIf { it.isFinite() && it >= 0.0 && it < 360.0 }
        if (trueBearing == null) {
            reportOnce("longitudinal-reference-unavailable", "IMU longitudinal acceleration unavailable: no accepted gps/fused course within 15 s to define the vehicle frame")
            return null
        }
        if (attitudeReferenceFrame == CMAttitudeReferenceFrameXTrueNorthZVertical) return trueBearing
        val declination = magneticDeclinationDegrees()?.takeIf { it.isFinite() }
        if (declination == null) {
            reportOnce("magnetic-declination-unavailable", "IMU longitudinal acceleration unavailable until CoreLocation true/magnetic heading calibration exists")
            return null
        }
        return (trueBearing - declination + 360.0) % 360.0
    }

    private fun sendActivity(activity: CMMotionActivity) {
        if (!trackingActive) return
        val ageSeconds = clock.wallTimeMs() / 1_000.0 - activity.startDate.unixTimeSeconds()
        if (!ageSeconds.isFinite() || ageSeconds !in 0.0..MAX_PEDOMETER_BATCH_AGE_SECONDS) {
            reportOnce("stale-activity", "Discarded stale or future-dated CoreMotion activity callback")
            return
        }
        val classification = when {
            activity.automotive -> Activity.IN_VEHICLE
            activity.walking || activity.running -> Activity.WALKING
            activity.stationary -> Activity.STILL
            else -> Activity.UNKNOWN
        }
        dispatch(TrackerEvent.ActivityChanged(classification))
    }

    private fun sendSteps(data: CMPedometerData) {
        if (!continuousSensorsActive) return
        val count = data.numberOfSteps.intValue
        val start = data.startDate.unixTimeSeconds()
        val end = data.endDate.unixTimeSeconds()
        val wallNow = clock.wallTimeMs() / 1_000.0
        val ageSeconds = wallNow - end
        val batchDuration = end - start
        if (count !in 1..10 || !batchDuration.isFinite() ||
            batchDuration < 0.0 || batchDuration > MAX_PEDOMETER_BATCH_AGE_SECONDS
        ) {
            reportOnce("unusable-step-batch", "Discarded CoreMotion step batch outside the 1..10 steps / 2 second window")
            return
        }
        if (!ageSeconds.isFinite() || ageSeconds !in 0.0..MAX_PEDOMETER_BATCH_AGE_SECONDS) {
            reportOnce("stale-step-batch", "Discarded stale or future-dated CoreMotion step batch")
            return
        }
        val observedAtContinuousSeconds = clock.monotonicSeconds() - ageSeconds
        dispatch(TrackerEvent.Steps(count, observedAtContinuousSeconds))
    }

    private fun stopContinuousSensors() {
        continuousSensorsActive = false
        motionManager.stopDeviceMotionUpdates()
        pedometer.stopPedometerUpdates()
    }

    private fun reportOnce(key: String, message: String) {
        if (reported.add(key)) {
            logger.error(message, null)
            dispatch(TrackerEvent.PlatformError(message))
        }
    }

    private data class Vector3(val x: Double, val y: Double, val z: Double)
    private data class Quaternion(val x: Double, val y: Double, val z: Double, val w: Double)

    private fun rotate(
        quaternion: Quaternion,
        x: Double,
        y: Double,
        z: Double,
    ): Vector3 {
        val tx = 2.0 * (quaternion.y * z - quaternion.z * y)
        val ty = 2.0 * (quaternion.z * x - quaternion.x * z)
        val tz = 2.0 * (quaternion.x * y - quaternion.y * x)
        return Vector3(
            x + quaternion.w * tx + quaternion.y * tz - quaternion.z * ty,
            y + quaternion.w * ty + quaternion.z * tx - quaternion.x * tz,
            z + quaternion.w * tz + quaternion.x * ty - quaternion.y * tx,
        )
    }

    private fun chooseNorthReferenceFrame(): CMAttitudeReferenceFrame? {
        val available = CMMotionManager.availableAttitudeReferenceFrames()
        return when {
            (available and CMAttitudeReferenceFrameXTrueNorthZVertical) != 0uL ->
                CMAttitudeReferenceFrameXTrueNorthZVertical
            (available and CMAttitudeReferenceFrameXMagneticNorthZVertical) != 0uL ->
                CMAttitudeReferenceFrameXMagneticNorthZVertical
            else -> null
        }
    }

    private companion object {
        const val STANDARD_GRAVITY = 9.80665
        const val WAKE_QUIET_METERS_PER_SECOND = 0.2
        const val WAKE_GRAVITY_ALPHA = 0.05
        const val MAX_WAKE_GRAVITY_AGE_SECONDS = 5.0
        const val MAX_ATTACHED_SENSOR_SKEW_SECONDS = 1.0
        const val DEGREES_TO_RADIANS = kotlin.math.PI / 180.0
        const val MAX_MOTION_SAMPLE_AGE_SECONDS = 1.0
        const val RESUME_GAP_THRESHOLD_SECONDS = 1.0
        const val MAX_PEDOMETER_BATCH_AGE_SECONDS = 2.0
    }
}
