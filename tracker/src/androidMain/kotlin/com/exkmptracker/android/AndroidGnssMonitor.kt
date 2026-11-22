package com.exkmptracker.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.exkmptracker.bloc.TrackerEvent
import com.exkmptracker.filter.strongCn0FromUsed
import com.exkmptracker.model.GnssEvidence
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Optional raw GNSS evidence; UTC is emitted only when the receiver exposes a leap-second count. */
class AndroidGnssMonitor internal constructor(
    context: Context,
    events: TrackerEventBus,
) : Closeable {
    private val dispatch: (TrackerEvent) -> Unit = events::dispatch
    private val report: (String, Throwable?) -> Unit = events::report
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val thread = HandlerThread("exkmp-tracker-gnss").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    @Volatile private var measurementsRegistered = false
    @Volatile private var statusRegistered = false
    private var stationary = true
    @Volatile private var usedCn0DbHz: Double? = null
    @Volatile private var usedCn0AtNanos = Long.MIN_VALUE
    private val callback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            if (closed.get()) return
            try {
                val clock = event.clock
                val utcTimeMs = if (clock.hasLeapSecond() && clock.hasFullBiasNanos() && clock.hasBiasNanos()) {
                    val biasNanos = clock.biasNanos.toLong()
                    GPS_EPOCH_MS + (clock.timeNanos - clock.fullBiasNanos - biasNanos) / 1_000_000.0 -
                        clock.leapSecond * 1_000.0
                } else null
                val measurements = event.measurements
                val cn0 = measurements.map { it.cn0DbHz.toDouble() }.filter { it.isFinite() && it > 0.0 }
                val agc = measurements.asSequence()
                    .filter { android.os.Build.VERSION.SDK_INT >= 26 && it.hasAutomaticGainControlLevelDb() }
                    .map { it.automaticGainControlLevelDb.toDouble() }
                    .filter { it.isFinite() }
                    .toList()
                val uncertaintyMs = (
                    (if (clock.hasTimeUncertaintyNanos()) clock.timeUncertaintyNanos else 0.0) +
                        (if (clock.hasBiasUncertaintyNanos()) clock.biasUncertaintyNanos else 0.0)
                    ) / 1_000_000.0
                val monotonicNanos = if (android.os.Build.VERSION.SDK_INT >= 29 &&
                    clock.hasElapsedRealtimeNanos()
                ) clock.elapsedRealtimeNanos else android.os.SystemClock.elapsedRealtimeNanos()
                val nowNanos = SystemClock.elapsedRealtimeNanos()
                val strongCn0 = usedCn0DbHz?.takeIf { nowNanos - usedCn0AtNanos in 0..CN0_MAX_AGE_NANOS }
                dispatch(TrackerEvent.Gnss(GnssEvidence(
                    monotonicSeconds = monotonicNanos / 1_000_000_000.0,
                    utcTimeMs = utcTimeMs,
                    uncertaintyMs = uncertaintyMs,
                    meanAgcDb = agc.takeIf { it.isNotEmpty() }?.average(),
                    meanCn0DbHz = cn0.takeIf { it.isNotEmpty() }?.average(),
                    strongCn0DbHz = strongCn0,
                )))
            } catch (error: Exception) {
                report("Unable to process GNSS measurements", error)
            }
        }
    }
    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            if (closed.get()) return
            try {
                val used = ArrayList<Double>(status.satelliteCount)
                for (index in 0 until status.satelliteCount) {
                    if (!status.usedInFix(index)) continue
                    val cn0 = status.getCn0DbHz(index).toDouble()
                    if (cn0.isFinite() && cn0 > 0.0) used.add(cn0)
                }
                val quality = strongCn0FromUsed(used) ?: return
                usedCn0DbHz = quality
                usedCn0AtNanos = SystemClock.elapsedRealtimeNanos()
                if (measurementsRegistered) return
                dispatch(TrackerEvent.Gnss(GnssEvidence(
                    monotonicSeconds = usedCn0AtNanos / 1_000_000_000.0,
                    utcTimeMs = null,
                    strongCn0DbHz = quality,
                )))
            } catch (error: Exception) {
                report("Unable to process GNSS status", error)
            }
        }
    }

    @Synchronized
    fun setStationary(value: Boolean) {
        if (closed.get() || stationary == value) return
        stationary = value
        if (value) stop() else start()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun start() {
        if (stationary || closed.get() || measurementsRegistered || statusRegistered) return
        if (android.os.Build.VERSION.SDK_INT < 24) {
            report("Raw GNSS measurements are not supported on this Android version", null)
            return
        }
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            report("Raw GNSS measurements unavailable without fine location permission", null)
            return
        }
        try {
            measurementsRegistered = locationManager.registerGnssMeasurementsCallback(callback, handler)
            if (!measurementsRegistered) report("Device did not register the GNSS measurement callback", null)
        } catch (error: Exception) {
            report("Raw GNSS measurements are unavailable on this device", error)
        }
        try {
            statusRegistered = locationManager.registerGnssStatusCallback(statusCallback, handler)
            if (!statusRegistered) report("Device did not register the GNSS status callback", null)
        } catch (error: Exception) {
            report("GNSS satellite status is unavailable on this device", error)
        }
    }

    @Synchronized
    private fun stop() {
        if (measurementsRegistered) {
            try {
                locationManager.unregisterGnssMeasurementsCallback(callback)
            } catch (error: Exception) {
                report("Unable to unregister GNSS measurements", error)
            } finally {
                measurementsRegistered = false
            }
        }
        if (statusRegistered) {
            try {
                locationManager.unregisterGnssStatusCallback(statusCallback)
            } catch (error: Exception) {
                report("Unable to unregister GNSS status", error)
            } finally {
                statusRegistered = false
            }
        }
        usedCn0DbHz = null
        usedCn0AtNanos = Long.MIN_VALUE
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        thread.quitSafely()
    }

    private companion object {
        const val GPS_EPOCH_MS = 315_964_800_000.0
        const val CN0_MAX_AGE_NANOS = 2_000_000_000L
    }
}
