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

/** Fused high-accuracy stream plus platform GPS, network, and passive-provider fallbacks. */
class AndroidLocationSource(
    context: Context,
    private val dispatch: (TrackerEvent) -> Unit,
    private val report: (String, Throwable?) -> Unit,
) : Closeable {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val fused by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LocationServices.getFusedLocationProviderClient(appContext)
    }
    private val thread = HandlerThread("exkmp-tracker-location").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private var stationary = true
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
        if (value) stopUpdates() else startUpdates()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startUpdates() {
        if (closed.get() || stationary) return
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            report("Location updates unavailable: grant fine or coarse location permission", null)
            return
        }
        requestPlatformProvider(LocationManager.GPS_PROVIDER, fine, LocationSource.gps)
        requestPlatformProvider(LocationManager.NETWORK_PROVIDER, fine || coarse, LocationSource.network)
        requestPlatformProvider(LocationManager.PASSIVE_PROVIDER, fine || coarse, LocationSource.fused)
        if (!fusedRequested) {
            try {
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
                    .setMaxUpdateDelayMillis(0)
                    .setMaxUpdateAgeMillis(0)
                    .setWaitForAccurateLocation(false)
                    .build()
                val task = fused.requestLocationUpdates(request, fusedCallback, thread.looper)
                fusedRequested = true
                task.addOnSuccessListener { }
                    .addOnFailureListener {
                        fusedRequested = false
                        report("Fused location request failed; direct GPS_PROVIDER updates are requested as fallback", it)
                        if (!platformListeners.containsKey(LocationManager.GPS_PROVIDER)) {
                            report("No active precise location provider is available; network fixes alone are not a precise fallback", it)
                        }
                    }
            } catch (error: Exception) {
                report("Unable to request fused location updates", error)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestPlatformProvider(provider: String, allowed: Boolean, source: LocationSource) {
        if (!allowed || platformListeners.containsKey(provider)) return
        try {
            if (!locationManager.isProviderEnabled(provider)) {
                if (provider == LocationManager.GPS_PROVIDER) {
                    report("GPS_PROVIDER is disabled; fused high-accuracy location may still be available", null)
                }
                return
            }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) = emit(location, source)
                @Deprecated("Deprecated in Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            }
            locationManager.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, 0f, listener, thread.looper)
            platformListeners[provider] = listener
        } catch (error: SecurityException) {
            report("Permission denied for $provider location provider", error)
        } catch (error: Exception) {
            report("Unable to request $provider location updates", error)
        }
    }

    @Synchronized
    private fun stopUpdates() {
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

    private fun hasPermission(permission: String) =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stationary = true
        stopUpdates()
        thread.quitSafely()
    }

    private companion object { const val UPDATE_INTERVAL_MS = 1_000L }
}
