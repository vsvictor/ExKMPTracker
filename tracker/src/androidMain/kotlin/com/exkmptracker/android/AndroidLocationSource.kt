package com.exkmptracker.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.exkmptracker.bloc.TrackerEvent
import com.exkmptracker.model.LocationFix
import com.exkmptracker.model.LocationSource
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fused request parameters. The first result is not withheld until a precise solution.
 * [maxUpdateAgeMs] stays inside the bloc's 10-second acceptance window, so a recent
 * cached fix can be shown immediately and is not rejected as stale.
 */
internal data class AndroidLocationRequestSpec(
    val intervalMs: Long,
    val maxUpdateAgeMs: Long,
    val waitForAccurateLocation: Boolean,
)

/** Must stay inside TrackerBloc's -2..10 second fix-age window. */
internal const val CACHED_FIX_MAX_AGE_MS = 5_000L

internal fun androidLocationRequestSpec(stationary: Boolean) = AndroidLocationRequestSpec(
    intervalMs = if (stationary) 5_000L else 1_000L,
    maxUpdateAgeMs = CACHED_FIX_MAX_AGE_MS,
    waitForAccurateLocation = false,
)

/**
 * One precise location stream. Fused high-accuracy is the primary source.
 * Platform GPS is requested only after fused location fails, and network only when
 * neither precise provider can be started. Passive updates are not used: they repeat
 * another app's fix and were previously labelled as an independent fused measurement.
 * Changing the stationary interval replaces the request parameters without removing
 * the callback, so the stream does not go silent and the 15-second estimate horizon
 * does not clear the map pin.
 */
class AndroidLocationSource internal constructor(
    context: Context,
    events: TrackerEventBus,
) : Closeable {
    private val dispatch: (TrackerEvent) -> Unit = events::dispatch
    private val report: (String, Throwable?) -> Unit = events::report
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val fused by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LocationServices.getFusedLocationProviderClient(appContext)
    }
    private val thread = HandlerThread("exkmp-tracker-location").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private var stationary = true
    private var requestGeneration = 0
    private var fusedSerial = 0
    private var fusedRequested = false
    private val platformListeners = mutableMapOf<String, LocationListener>()
    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { emit(it, LocationSource.fused) }
        }
    }

    @Synchronized
    fun setStationary(value: Boolean) {
        if (closed.get() || stationary == value) return
        stationary = value
        // Removing the callback starts a new fused request. That request used to wait
        // again for an accurate fix, the estimate expired, and the map hid the pin.
        if (fusedRequested || platformListeners.isNotEmpty()) retarget() else startUpdates()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startUpdates() {
        if (closed.get()) return
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            report("Location updates unavailable: grant fine or coarse location permission", null)
            return
        }
        val generation = ++requestGeneration
        val interval = currentInterval()
        if (fine && requestFused(interval, generation)) return
        if (requestPlatformProvider(LocationManager.GPS_PROVIDER, fine, LocationSource.gps, interval)) return
        report("No active precise location provider is available; network fixes alone are not a precise fallback", null)
        requestPlatformProvider(LocationManager.NETWORK_PROVIDER, fine || coarse, LocationSource.network, interval)
    }

    @SuppressLint("MissingPermission")
    private fun retarget() {
        if (closed.get()) return
        val interval = currentInterval()
        if (fusedRequested) {
            requestFused(interval, requestGeneration)
            return
        }
        platformListeners.keys.toList().forEach { provider ->
            val source = if (provider == LocationManager.GPS_PROVIDER) LocationSource.gps else LocationSource.network
            requestPlatformProvider(provider, allowed = true, source = source, intervalMs = interval)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFused(intervalMs: Long, generation: Int): Boolean {
        return try {
            val spec = androidLocationRequestSpec(stationary)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs)
                .setMaxUpdateDelayMillis(0)
                .setMaxUpdateAgeMillis(spec.maxUpdateAgeMs)
                .setWaitForAccurateLocation(spec.waitForAccurateLocation)
                .build()
            val serial = ++fusedSerial
            val task = fused.requestLocationUpdates(request, fusedCallback, thread.looper)
            fusedRequested = true
            task.addOnFailureListener {
                handler.post {
                    synchronized(this) {
                        if (closed.get() || generation != requestGeneration || serial != fusedSerial) return@synchronized
                        fusedRequested = false
                        report("Fused location request failed; direct GPS_PROVIDER updates are requested as fallback", it)
                        val interval = currentInterval()
                        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (!requestPlatformProvider(LocationManager.GPS_PROVIDER, fine, LocationSource.gps, interval)) {
                            report("No active precise location provider is available; network fixes alone are not a precise fallback", it)
                            requestPlatformProvider(
                                LocationManager.NETWORK_PROVIDER,
                                fine || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
                                LocationSource.network,
                                interval,
                            )
                        }
                    }
                }
            }
            true
        } catch (error: Exception) {
            fusedRequested = false
            report("Unable to request fused location updates", error)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestPlatformProvider(
        provider: String,
        allowed: Boolean,
        source: LocationSource,
        intervalMs: Long,
    ): Boolean {
        if (!allowed) return false
        platformListeners[provider]?.let { existing ->
            return try {
                locationManager.requestLocationUpdates(provider, intervalMs, 0f, existing, thread.looper)
                true
            } catch (error: Exception) {
                report("Unable to retarget $provider location updates", error)
                false
            }
        }
        return try {
            if (!locationManager.isProviderEnabled(provider)) {
                if (provider == LocationManager.GPS_PROVIDER && !fusedRequested) {
                    report("GPS_PROVIDER is disabled", null)
                }
                false
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) = emit(location, source)
                    @Deprecated("Deprecated in Android")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                }
                locationManager.requestLocationUpdates(provider, intervalMs, 0f, listener, thread.looper)
                platformListeners[provider] = listener
                true
            }
        } catch (error: SecurityException) {
            report("Permission denied for $provider location provider", error)
            false
        } catch (error: Exception) {
            report("Unable to request $provider location updates", error)
            false
        }
    }

    @Synchronized
    private fun stopUpdates() {
        requestGeneration++
        if (fusedRequested) {
            fused.removeLocationUpdates(fusedCallback)
                .addOnFailureListener { report("Unable to stop fused location updates", it) }
            fusedRequested = false
        }
        platformListeners.forEach { (provider, listener) ->
            try {
                locationManager.removeUpdates(listener)
            } catch (error: Exception) {
                report("Unable to stop $provider location updates", error)
            }
        }
        platformListeners.clear()
    }

    private fun emit(location: Location, source: LocationSource) {
        if (closed.get()) return
        val fix = LocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.toDouble(),
            observedAtMs = location.time,
            monotonicSeconds = location.elapsedRealtimeNanos / 1_000_000_000.0,
            source = source,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else -1.0,
            bearingDegrees = if (location.hasBearing()) location.bearing.toDouble() else -1.0,
            speedAccuracyMps = if (android.os.Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else null,
            bearingAccuracyDegrees = if (android.os.Build.VERSION.SDK_INT >= 26 && location.hasBearingAccuracy()) location.bearingAccuracyDegrees.toDouble() else null,
            isMock = location.isFromMockProvider,
        )
        dispatch(TrackerEvent.Position(fix))
    }

    private fun currentInterval() = androidLocationRequestSpec(stationary).intervalMs

    private fun hasPermission(permission: String) =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stationary = true
        stopUpdates()
        thread.quitSafely()
    }
}
