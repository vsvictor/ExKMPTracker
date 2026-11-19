package com.exkmptracker.android

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.exkmptracker.bloc.TrackerEvent
import com.exkmptracker.model.LocationSource
import com.exkmptracker.model.TrackEstimate
import com.exkmptracker.model.MotionSample
import com.exkmptracker.ports.SensorController
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

/** Sensor fusion maps acceleration through the device attitude into earth coordinates. */
class AndroidSensorController(
    context: Context,
    private val dispatch: (TrackerEvent) -> Unit,
    private val report: (String, Throwable?) -> Unit,
) : SensorController, Closeable {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("exkmp-tracker-motion").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val linearSensor = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val magneticSensor = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val stepSensor = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val pressureSensor = manager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    @Volatile private var stationary = true
    private var significantMotionRegistered = false
    private var gravityInitialized = false
    private var lastEmissionNanos = 0L
    private var accelerationTimestampNanos = 0L
    private var linearTimestampNanos = 0L
    private var attitudeTimestampNanos = 0L
    private var yawTimestampNanos = 0L
    private var pressureTimestampNanos = 0L
    private var emittedPressureTimestampNanos = 0L
    private var gravityTimestampNanos = 0L
    private var pressureHpa: Double? = null
    private var yawRateRadians: Double? = null
    private var latestCourseDegrees: Double? = null
    private var courseTimestampNanos = 0L
    private var declinationDegrees: Double? = null
    private var stationaryWake = StationaryWake()
    private var warnedNoAttitude = false
    private var warnedNoCourse = false
    private var warnedNoGyroscope = false
    private val gravity = FloatArray(3)
    private val lowPassGravity = FloatArray(3)
    private val magnetic = FloatArray(3)
    private val acceleration = FloatArray(3)
    private val linearAcceleration = FloatArray(3)
    private val angularVelocity = FloatArray(3)
    private var hasAcceleration = false
    private var hasLinear = false
    private var hasGravity = false
    private var hasMagnetic = false
    private var rotationMatrix = FloatArray(9)
    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            synchronized(this@AndroidSensorController) {
            if (closed.get() || (stationary && event.sensor.type != Sensor.TYPE_ACCELEROMETER)) return
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    if (event.values.any { !it.isFinite() }) return
                    event.values.copyInto(acceleration)
                    hasAcceleration = true
                    accelerationTimestampNanos = event.timestamp
                    if (stationary) {
                        processStationary(event.timestamp)
                    } else {
                        updateLowPassGravity(event.values)
                        process(event.timestamp)
                    }
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    event.values.copyInto(linearAcceleration)
                    hasLinear = true
                    linearTimestampNanos = event.timestamp
                    process(event.timestamp)
                }
                Sensor.TYPE_GRAVITY -> {
                    if (event.values.any { !it.isFinite() }) return
                    event.values.copyInto(gravity)
                    hasGravity = true
                    gravityTimestampNanos = event.timestamp
                    process(event.timestamp)
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    event.values.copyInto(magnetic)
                    hasMagnetic = true
                    if (rotationSensor == null && hasGravity) updateFallbackAttitude(event.timestamp)
                    process(event.timestamp)
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    attitudeTimestampNanos = event.timestamp
                    process(event.timestamp)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    event.values.copyInto(angularVelocity)
                    if (attitudeTimestampNanos > 0L &&
                        event.timestamp - attitudeTimestampNanos in 0L..MAX_SENSOR_AGE_NANOS
                    ) {
                        val worldUpAngularVelocity =
                            rotationMatrix[6] * angularVelocity[0] +
                                rotationMatrix[7] * angularVelocity[1] +
                                rotationMatrix[8] * angularVelocity[2]
                        // Android gyro is right-handed about world up; the EKF heading is clockwise.
                        yawRateRadians = (-worldUpAngularVelocity.toDouble()).takeIf { it.isFinite() }
                        yawTimestampNanos = attitudeTimestampNanos
                    }
                    process(event.timestamp)
                }
                Sensor.TYPE_STEP_DETECTOR ->
                    dispatch(TrackerEvent.Steps(count = 1, monotonicSeconds = event.timestamp / 1_000_000_000.0))
                Sensor.TYPE_PRESSURE -> {
                    if (event.timestamp > pressureTimestampNanos) {
                        pressureHpa = event.values[0].toDouble().takeIf { it.isFinite() && it > 0.0 }
                        pressureTimestampNanos = event.timestamp
                    }
                }
            }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private val significantMotion = manager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private val significantMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            significantMotionRegistered = false
            if (closed.get()) return
            handler.post {
                if (stationary && !closed.get()) registerSignificantMotion()
            }
        }
    }

    init {
        if (accelerometer == null) report("Accelerometer is unavailable; motion fusion is disabled", null)
        if (rotationSensor == null && (gravitySensor == null || magneticSensor == null)) {
            report("No rotation-vector or gravity/magnetic attitude sensors; earth-frame acceleration is unavailable", null)
        }
        if (stepSensor == null) report("Step detector is unavailable", null)
        if (pressureSensor == null) report("Barometer is unavailable", null)
    }

    @Synchronized
    override fun setStationary(stationary: Boolean) {
        if (closed.get() || this.stationary == stationary) return
        if (stationary) {
            val now = SystemClock.elapsedRealtimeNanos()
            val trustedGravity = when {
                hasGravity && now - gravityTimestampNanos in 0L..MAX_SENSOR_AGE_NANOS ->
                    AccelerationVector(gravity)
                hasAcceleration && gravityInitialized &&
                    now - accelerationTimestampNanos in 0L..MAX_SENSOR_AGE_NANOS ->
                    AccelerationVector(lowPassGravity)
                else -> stationaryWake.gravity
            }
            stationaryWake = StationaryWake(gravity = trustedGravity)
        }
        this.stationary = stationary
        unregisterAll()
        hasLinear = false
        hasGravity = false
        hasMagnetic = false
        hasAcceleration = false
        accelerationTimestampNanos = 0L
        linearTimestampNanos = 0L
        attitudeTimestampNanos = 0L
        yawTimestampNanos = 0L
        yawRateRadians = null
        pressureTimestampNanos = 0L
        pressureHpa = null
        lastEmissionNanos = 0L
        registerForCurrentState()
    }

    /** Only the bloc's accepted precise estimates may refresh the earth-frame calibration. */
    @Synchronized
    internal fun updateAcceptedEstimate(estimate: TrackEstimate?) {
        if (closed.get()) return
        if (estimate == null || estimate.source == LocationSource.stationary) {
            latestCourseDegrees = null
            declinationDegrees = null
            return
        }
        if (estimate.source != LocationSource.gps && estimate.source != LocationSource.fused) return
        if (!estimate.monotonicSeconds.isFinite()) return
        val timestamp = (estimate.monotonicSeconds * 1_000_000_000.0).toLong()
        if (timestamp <= courseTimestampNanos) return
        courseTimestampNanos = timestamp
        latestCourseDegrees = null
        declinationDegrees = null
        if (estimate.latitude !in -90.0..90.0 || estimate.longitude !in -180.0..180.0 ||
            !estimate.speedMps.isFinite() || estimate.speedMps < MIN_COURSE_SPEED_MPS ||
            estimate.bearingDegrees !in 0.0..<360.0
        ) return
        try {
            declinationDegrees = GeomagneticField(
                estimate.latitude.toFloat(),
                estimate.longitude.toFloat(),
                0f,
                System.currentTimeMillis(),
            ).declination.toDouble().takeIf { it.isFinite() }
            if (declinationDegrees != null) latestCourseDegrees = estimate.bearingDegrees
        } catch (error: Exception) {
            report("Unable to calculate magnetic declination", error)
        }
    }

    @Synchronized
    private fun registerForCurrentState() {
        if (closed.get()) return
        if (stationary) {
            if (accelerometer != null) register(accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            registerSignificantMotion()
            return
        }
        register(accelerometer, SensorManager.SENSOR_DELAY_GAME)
        register(linearSensor, SensorManager.SENSOR_DELAY_GAME)
        register(rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        register(gyroscope, SensorManager.SENSOR_DELAY_GAME)
        register(gravitySensor, SensorManager.SENSOR_DELAY_GAME)
        register(magneticSensor, SensorManager.SENSOR_DELAY_GAME)
        register(stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        register(pressureSensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun register(sensor: Sensor?, delay: Int) {
        if (sensor == null) return
        try {
            if (!manager.registerListener(listener, sensor, delay, handler)) {
                report("Sensor registration failed for ${sensor.name}", null)
            }
        } catch (error: Exception) {
            report("Unable to register sensor ${sensor.name}", error)
        }
    }

    private fun registerSignificantMotion() {
        if (significantMotion == null) {
            report("No hardware significant-motion wake sensor; low-rate accelerometer wake is being used", null)
            return
        }
        if (significantMotionRegistered) return
        try {
            significantMotionRegistered = manager.requestTriggerSensor(significantMotionListener, significantMotion)
            if (!significantMotionRegistered) report("Significant-motion wake sensor could not be activated", null)
        } catch (error: Exception) {
            report("Unable to activate significant-motion wake sensor", error)
        }
    }

    private fun unregisterAll() {
        manager.unregisterListener(listener)
        if (significantMotionRegistered) {
            try {
                manager.cancelTriggerSensor(significantMotionListener, significantMotion)
            } catch (error: Exception) {
                report("Unable to cancel significant-motion wake sensor", error)
            }
            significantMotionRegistered = false
        }
    }

    private fun updateLowPassGravity(values: FloatArray) {
        val alpha = 0.90f
        if (!gravityInitialized) {
            values.copyInto(lowPassGravity)
            gravityInitialized = true
        } else {
            for (index in 0..2) lowPassGravity[index] = alpha * lowPassGravity[index] + (1f - alpha) * values[index]
        }
    }

    private fun updateFallbackAttitude(timestampNanos: Long) {
        val gravityVector = if (hasGravity) gravity else lowPassGravity
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravityVector, magnetic)) {
            attitudeTimestampNanos = minOf(timestampNanos, accelerationTimestampNanos.takeIf { it > 0L } ?: timestampNanos)
        }
    }

    private fun processStationary(timestampNanos: Long) {
        if (SystemClock.elapsedRealtimeNanos() - timestampNanos !in 0L..MAX_SENSOR_AGE_NANOS ||
            timestampNanos - lastEmissionNanos < MOTION_INTERVAL_NANOS
        ) return
        lastEmissionNanos = timestampNanos
        val hadBaseline = stationaryWake.gravity != null
        stationaryWake = sampleStationaryWake(stationaryWake, AccelerationVector(acceleration))
        if (!hadBaseline) return
        emitSample(timestampNanos, null, null, stationaryWake.magnitude, null)
        if (stationaryWake.shouldWake) setStationary(false)
    }

    private fun process(timestampNanos: Long) {
        if (!hasAcceleration && !hasLinear) return
        if (rotationSensor == null && hasMagnetic && gravityInitialized && attitudeTimestampNanos == 0L) {
            updateFallbackAttitude(timestampNanos)
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val useLinear = hasLinear && now - linearTimestampNanos in 0L..MAX_SENSOR_AGE_NANOS &&
            linearAcceleration.all { it.isFinite() }
        val sampleTimestamp = if (useLinear) linearTimestampNanos else accelerationTimestampNanos
        if (sampleTimestamp <= 0L || now - sampleTimestamp !in 0L..MAX_SENSOR_AGE_NANOS ||
            sampleTimestamp - lastEmissionNanos < MOTION_INTERVAL_NANOS
        ) return
        val gravityVector = if (hasGravity && sampleTimestamp - gravityTimestampNanos in 0L..MAX_SENSOR_AGE_NANOS) {
            gravity
        } else {
            lowPassGravity
        }
        val raw = if (useLinear) linearAcceleration else FloatArray(3) { acceleration[it] - gravityVector[it] }
        val magnitude = sqrt(raw.sumOf { (it * it).toDouble() })
        if (!magnitude.isFinite()) return
        lastEmissionNanos = sampleTimestamp
        val course = latestCourseDegrees
        val declination = declinationDegrees
        val freshCourse = course?.takeIf {
            courseTimestampNanos > 0L && now - courseTimestampNanos in 0L..MAX_COURSE_AGE_NANOS
        }
        val attitudeFresh = attitudeTimestampNanos > 0L &&
            now - attitudeTimestampNanos in 0L..MAX_SENSOR_AGE_NANOS
        val yaw = yawRateRadians?.takeIf {
            attitudeFresh && yawTimestampNanos > 0L &&
                now - yawTimestampNanos in 0L..MAX_SENSOR_AGE_NANOS
        }
        if (!attitudeFresh && !warnedNoAttitude) {
            warnedNoAttitude = true
            report("Earth-frame orientation unavailable or stale; navigation controls omitted", null)
        }
        if ((freshCourse == null || declination == null) && !warnedNoCourse) {
            warnedNoCourse = true
            report("No fresh accepted precise course or magnetic declination; longitudinal acceleration omitted", null)
        }
        if (attitudeFresh && yaw == null && !warnedNoGyroscope) {
            warnedNoGyroscope = true
            report("Gyroscope unavailable or stale; yaw control omitted", null)
        }

        var longitudinal: Double? = null
        var compass: Double? = null
        if (attitudeFresh && declination != null) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            compass = ((Math.toDegrees(orientation[0].toDouble()) + declination + 360.0) % 360.0)
                .takeIf { it.isFinite() }
            if (freshCourse != null) {
                val magneticEast = rotationMatrix[0] * raw[0] + rotationMatrix[1] * raw[1] + rotationMatrix[2] * raw[2]
                val magneticNorth = rotationMatrix[3] * raw[0] + rotationMatrix[4] * raw[1] + rotationMatrix[5] * raw[2]
                val declinationRadians = Math.toRadians(declination)
                val trueEast = magneticEast * cos(declinationRadians) + magneticNorth * sin(declinationRadians)
                val trueNorth = -magneticEast * sin(declinationRadians) + magneticNorth * cos(declinationRadians)
                val courseRadians = Math.toRadians(freshCourse)
                longitudinal = (trueEast * sin(courseRadians) + trueNorth * cos(courseRadians)).takeIf { it.isFinite() }
                warnedNoCourse = false
            }
        }
        emitSample(sampleTimestamp, longitudinal, yaw, magnitude, compass)
    }

    private fun emitSample(timestampNanos: Long, longitudinal: Double?, yaw: Double?, magnitude: Double, compassValue: Double?) {
        val pressure = pressureHpa?.takeIf {
            pressureTimestampNanos > emittedPressureTimestampNanos &&
                timestampNanos - pressureTimestampNanos in 0L..MAX_SENSOR_AGE_NANOS
        }
        if (pressure != null) emittedPressureTimestampNanos = pressureTimestampNanos
        dispatch(TrackerEvent.Motion(MotionSample(
            monotonicSeconds = timestampNanos / 1_000_000_000.0,
            longitudinalAcceleration = longitudinal,
            yawRateRadians = yaw,
            linearAccelerationMagnitude = magnitude,
            pressureHpa = pressure,
            compassDegrees = compassValue,
        )))
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        unregisterAll()
        thread.quitSafely()
    }

    private companion object {
        const val MOTION_INTERVAL_NANOS = 20_000_000L
        const val MAX_SENSOR_AGE_NANOS = 1_000_000_000L
        const val MAX_COURSE_AGE_NANOS = 15_000_000_000L
        const val MIN_COURSE_SPEED_MPS = 1.0
    }
}
