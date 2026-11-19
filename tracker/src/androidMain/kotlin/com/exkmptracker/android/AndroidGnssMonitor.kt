package com.exkmptracker.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import com.exkmptracker.bloc.TrackerEvent
import com.exkmptracker.model.GnssEvidence
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Optional raw GNSS evidence; UTC is emitted only when the receiver exposes a leap-second count. */
class AndroidGnssMonitor(
    context: Context,
    private val dispatch: (TrackerEvent) -> Unit,
    private val report: (String, Throwable?) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val thread = HandlerThread("exkmp-tracker-gnss").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private var registered = false
    private var stationary = true
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
                dispatch(TrackerEvent.Gnss(GnssEvidence(
                    monotonicSeconds = monotonicNanos / 1_000_000_000.0,
                    utcTimeMs = utcTimeMs,
                    uncertaintyMs = uncertaintyMs,
                    meanAgcDb = agc.takeIf { it.isNotEmpty() }?.average(),
                    meanCn0DbHz = cn0.takeIf { it.isNotEmpty() }?.average(),
                )))
            } catch (error: Exception) {
                report("Unable to process GNSS measurements", error)
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
        if (stationary || closed.get() || registered) return
        if (android.os.Build.VERSION.SDK_INT < 24) {
            report("Raw GNSS measurements are not supported on this Android version", null)
            return
        }
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            report("Raw GNSS measurements unavailable without fine location permission", null)
            return
        }
        try {
            registered = locationManager.registerGnssMeasurementsCallback(callback, handler)
            if (!registered) report("Device did not register the GNSS measurement callback", null)
        } catch (error: Exception) {
            report("Raw GNSS measurements are unavailable on this device", error)
        }
    }

    @Synchronized
    private fun stop() {
        if (!registered) return
        try {
            locationManager.unregisterGnssMeasurementsCallback(callback)
        } catch (error: Exception) {
            report("Unable to unregister GNSS measurements", error)
        } finally {
            registered = false
        }
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        thread.quitSafely()
    }

    private companion object { const val GPS_EPOCH_MS = 315_964_800_000.0 }
}
