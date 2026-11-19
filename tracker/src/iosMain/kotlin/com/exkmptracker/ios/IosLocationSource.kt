@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.exkmptracker.ios

import com.exkmptracker.bloc.TrackerEvent
import com.exkmptracker.model.LocationFix
import com.exkmptracker.model.LocationSource
import com.exkmptracker.ports.TrackerClock
import com.exkmptracker.ports.TrackerLogger
import com.exkmptracker.model.TrackerConfig
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLActivityTypeAutomotiveNavigation
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLHeadingFilterNone
import platform.CoreLocation.kCLLocationAccuracyBestForNavigation
import platform.Foundation.NSError
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.darwin.NSObject

private fun iosMajorVersion(): Int =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion.toInt() }

/** CoreLocation adapter. This class and its manager are created/used on the main queue. */
internal class IosLocationSource(
    private val clock: TrackerClock,
    private val config: TrackerConfig,
    private val dispatch: (TrackerEvent) -> Unit,
    private val logger: TrackerLogger,
) {
    private val manager = CLLocationManager()
    private val delegate = Delegate(
        ::onLocations,
        ::onAuthorizationChanged,
        ::onFailure,
        ::onHeading,
    )
    var magneticDeclinationDegrees: Double? = null
        private set

    /** Latest valid true-north compass heading with its observation time in the tracker clock. */
    var latestHeading: IosHeadingObservation? = null
        private set

    init {
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = CLActivityTypeAutomotiveNavigation
        manager.pausesLocationUpdatesAutomatically = false
    }

    fun start(backgroundLocationConfigured: Boolean): Boolean {
        if (!CLLocationManager.locationServicesEnabled()) {
            reportError("iOS Location Services are disabled")
            return false
        }

        val authorization = currentAuthorizationStatus()
        if (authorization != kCLAuthorizationStatusAuthorizedAlways &&
            authorization != kCLAuthorizationStatusAuthorizedWhenInUse
        ) {
            reportError(
                when (authorization) {
                    kCLAuthorizationStatusDenied -> "Location permission is denied; enable it in Settings"
                    kCLAuthorizationStatusRestricted -> "Location permission is restricted on this device"
                    kCLAuthorizationStatusNotDetermined -> "Location permission is not determined; request it in the host app before starting tracking"
                    else -> "Location permission is unavailable"
                },
            )
            return false
        }

        val hostHasBackgroundLocationSetup = backgroundLocationConfigured &&
            hasBackgroundLocationInfoPlistConfiguration()
        val canRunInBackground = hostHasBackgroundLocationSetup &&
            authorization == kCLAuthorizationStatusAuthorizedAlways
        manager.allowsBackgroundLocationUpdates = canRunInBackground
        if (backgroundLocationConfigured && !canRunInBackground) {
            reportError("Background location needs UIBackgroundModes=location, an Always-location usage description, and Always authorization; continuing foreground-only")
        }
        manager.startUpdatingLocation()
        if (CLLocationManager.headingAvailable()) {
            manager.headingFilter = kCLHeadingFilterNone
            manager.startUpdatingHeading()
        } else {
            reportError("iOS compass heading is unavailable; walking step prediction cannot be used")
        }
        return true
    }

    fun stop() {
        latestHeading = null
        manager.stopUpdatingLocation()
        manager.stopUpdatingHeading()
        manager.allowsBackgroundLocationUpdates = false
    }

    private fun onLocations(locations: List<*>) {
        val location = locations.lastOrNull() as? CLLocation ?: return
        val observedAtMs = (location.timestamp.unixTimeSeconds() * 1_000.0).toLong()
        val ageSeconds = (clock.wallTimeMs() - observedAtMs) / 1_000.0
        if (!ageSeconds.isFinite() || ageSeconds > MAX_SAMPLE_AGE_SECONDS || ageSeconds < -MAX_FUTURE_SKEW_SECONDS) {
            reportError("Discarded stale or future-dated iOS location sample")
            return
        }

        val (latitude, longitude) = location.coordinate.useContents { latitude to longitude }
        val accuracy = location.horizontalAccuracy
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
            !accuracy.isFinite() || accuracy <= 0.0
        ) {
            reportError("Discarded invalid iOS location sample")
            return
        }

        val fullAccuracy = iosMajorVersion() >= 14 &&
            manager.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy
        val isPreciseNativeLocation = fullAccuracy && accuracy <= config.maxGnssAccuracyMeters
        val source = if (isPreciseNativeLocation) LocationSource.gps else LocationSource.fused
        val simulated = isSimulatedLocation(location)
        val monotonicSampleTime = clock.monotonicSeconds() - ageSeconds

        dispatch(
            TrackerEvent.Position(
                LocationFix(
                    latitude = latitude,
                    longitude = longitude,
                    accuracyMeters = accuracy,
                    observedAtMs = observedAtMs,
                    monotonicSeconds = monotonicSampleTime,
                    source = source,
                    speedMps = location.speed.takeIf { it.isFinite() && it >= 0.0 } ?: -1.0,
                    bearingDegrees = location.course.takeIf { it.isFinite() && it >= 0.0 } ?: -1.0,
                    speedAccuracyMps = location.speedAccuracy.takeIf { it.isFinite() && it >= 0.0 },
                    bearingAccuracyDegrees = if (iosMajorVersion() >= 13) {
                        location.courseAccuracy.takeIf { it.isFinite() && it >= 0.0 }
                    } else {
                        null
                    },
                    isMock = simulated,
                ),
            ),
        )
    }

    private fun isSimulatedLocation(location: CLLocation): Boolean {
        if (iosMajorVersion() < 15) return false
        return location.sourceInformation?.isSimulatedBySoftware ?: false
    }

    private fun hasBackgroundLocationInfoPlistConfiguration(): Boolean {
        val modes = NSBundle.mainBundle.objectForInfoDictionaryKey("UIBackgroundModes") as? List<*>
        val alwaysDescription =
            NSBundle.mainBundle.objectForInfoDictionaryKey("NSLocationAlwaysAndWhenInUseUsageDescription") as? String
        return modes?.contains("location") == true && !alwaysDescription.isNullOrBlank()
    }

    private fun currentAuthorizationStatus(): CLAuthorizationStatus =
        if (iosMajorVersion() >= 14) {
            manager.authorizationStatus
        } else {
            CLLocationManager.authorizationStatus()
        }

    private fun onAuthorizationChanged(status: CLAuthorizationStatus) {
        if (status == kCLAuthorizationStatusDenied || status == kCLAuthorizationStatusRestricted) {
            reportError("iOS location authorization changed to denied or restricted")
        }
    }

    private fun onFailure(error: NSError) {
        reportError("CoreLocation failed: ${error.localizedDescription}")
    }

    private fun onHeading(heading: CLHeading) {
        val accuracy = heading.headingAccuracy
        val magnetic = heading.magneticHeading
        // Negative headingAccuracy means CoreLocation considers the heading invalid.
        if (!accuracy.isFinite() || accuracy < 0.0 || !magnetic.isFinite() || magnetic !in 0.0..<360.0) return
        val trueHeading = heading.trueHeading.takeIf { it.isFinite() && it in 0.0..<360.0 }
        if (trueHeading != null) {
            var correction = trueHeading - magnetic
            if (correction > 180.0) correction -= 360.0
            if (correction < -180.0) correction += 360.0
            magneticDeclinationDegrees = correction
        }
        // trueHeading is negative until CoreLocation has a location; then the last
        // CoreLocation-derived declination is used, otherwise no compass value is offered.
        val northHeading = trueHeading
            ?: magneticDeclinationDegrees?.let { ((magnetic + it) % 360.0 + 360.0) % 360.0 }
            ?: return
        if (accuracy > MAX_HEADING_ACCURACY_DEGREES) return
        val ageSeconds = clock.wallTimeMs() / 1_000.0 - heading.timestamp.unixTimeSeconds()
        if (!ageSeconds.isFinite() || ageSeconds !in -MAX_FUTURE_SKEW_SECONDS..MAX_HEADING_AGE_SECONDS) return
        latestHeading = IosHeadingObservation(
            degrees = northHeading,
            accuracyDegrees = accuracy,
            monotonicSeconds = clock.monotonicSeconds() - ageSeconds.coerceAtLeast(0.0),
        )
    }

    private fun reportError(message: String) {
        logger.error(message, null)
        dispatch(TrackerEvent.PlatformError(message))
    }

    private class Delegate(
        private val locations: (List<*>) -> Unit,
        private val authorizationChanged: (CLAuthorizationStatus) -> Unit,
        private val failure: (NSError) -> Unit,
        private val heading: (CLHeading) -> Unit,
    ) : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            locations(didUpdateLocations)
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            failure(didFailWithError)
        }

        override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
            heading(didUpdateHeading)
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            authorizationChanged(currentAuthorizationStatus(manager))
        }

        override fun locationManager(
            manager: CLLocationManager,
            didChangeAuthorizationStatus: CLAuthorizationStatus,
        ) {
            authorizationChanged(didChangeAuthorizationStatus)
        }

        private fun currentAuthorizationStatus(manager: CLLocationManager): CLAuthorizationStatus =
            if (iosMajorVersion() >= 14) {
                manager.authorizationStatus
            } else {
                CLLocationManager.authorizationStatus()
            }
    }

    private companion object {
        const val MAX_SAMPLE_AGE_SECONDS = 30.0
        const val MAX_FUTURE_SKEW_SECONDS = 5.0
        const val MAX_HEADING_AGE_SECONDS = 1.0
        const val MAX_HEADING_ACCURACY_DEGREES = 45.0
    }
}

/** A validated CLHeading converted to true north and the tracker monotonic clock domain. */
internal data class IosHeadingObservation(
    val degrees: Double,
    val accuracyDegrees: Double,
    val monotonicSeconds: Double,
)
