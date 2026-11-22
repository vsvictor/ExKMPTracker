package com.exkmptracker.filter

import com.exkmptracker.model.*
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class TrackingFilterTests {
    private fun fix(time: Double = 1.0, lat: Double = 50.0, lon: Double = 30.0,
                    accuracy: Double = 5.0, speed: Double = 0.0, heading: Double = 0.0,
                    source: LocationSource = LocationSource.gps, mock: Boolean = false) =
        LocationFix(lat, lon, accuracy, (time * 1000).toLong(), time, source, speed, heading, isMock = mock)

    @Test fun initialAccuracyUses68PercentRadius() {
        val result = assertNotNull(TrackingFilter().fix(fix(accuracy = 8.0)))
        assertEquals(8.0, result.accuracyMeters, 1e-8)
    }

    @Test fun sourceSpecificAccuracyThresholdsAreInclusive() {
        assertNotNull(TrackingFilter().fix(fix(accuracy = 65.0)))
        assertNull(TrackingFilter().fix(fix(accuracy = 65.001)))
        assertNotNull(TrackingFilter().fix(fix(accuracy = 150.0, source = LocationSource.network)))
        assertNull(TrackingFilter().fix(fix(accuracy = 150.001, source = LocationSource.network)))
    }

    @Test fun positionSigmaHasFloorAndNetworkIsWidened() {
        val gnss = assertNotNull(TrackingFilter().fix(fix(accuracy = 1.0)))
        assertEquals(3.0, gnss.accuracyMeters, 1e-6)
        val network = assertNotNull(TrackingFilter().fix(fix(accuracy = 50.0, source = LocationSource.network)))
        assertEquals(hypot(50.0, 30.0), network.accuracyMeters, 1e-6)
    }

    @Test fun slowSpeedIsIntegratedInsteadOfSnappedToZero() {
        val filter = TrackingFilter()
        filter.fix(fix(speed = 0.2))
        val predicted = assertNotNull(filter.predict(2.0, 0.0, 0.0))
        assertTrue(predicted.speedMps > 0.1, "speed was ${predicted.speedMps}")
    }

    @Test fun courseUpdatesWhileWalking() {
        val filter = TrackingFilter()
        filter.fix(fix(speed = 1.0, heading = 0.0).copy(bearingAccuracyDegrees = 1.0))
        val next = assertNotNull(filter.fix(fix(time = 2.0, lat = 50.000009, speed = 1.0, heading = 90.0)
            .copy(bearingAccuracyDegrees = 1.0)))
        assertTrue(next.bearingDegrees > 10.0, "bearing was ${next.bearingDegrees}")
    }

    @Test fun threeAgreeingOutliersReplaceABiasedLock() {
        val filter = TrackingFilter()
        filter.fix(fix())
        assertNull(filter.fix(fix(time = 2.0, lat = 50.01)))
        assertNull(filter.fix(fix(time = 3.0, lat = 50.01)))
        val recovered = assertNotNull(filter.fix(fix(time = 4.0, lat = 50.01)))
        assertEquals(50.01, recovered.latitude, 1e-9)
    }

    @Test fun coarseClusterCannotReplaceAPreciseLock() {
        val filter = TrackingFilter()
        val locked = assertNotNull(filter.fix(fix(accuracy = 5.0)))
        repeat(3) { index ->
            assertNull(filter.fix(fix(time = 2.0 + index, lat = 50.01, accuracy = 59.0)))
        }
        val kept = assertNotNull(filter.fix(fix(time = 6.0, accuracy = 5.0)))
        assertEquals(locked.latitude, kept.latitude, 1e-6)
        assertTrue(kept.accuracyMeters >= 3.0)
    }

    @Test fun coarseFixCannotReinitializeWithinAMinuteOfAPreciseFix() {
        val filter = TrackingFilter()
        filter.fix(fix(accuracy = 5.0))
        assertNull(filter.fix(fix(time = 20.0, lat = 50.0002, accuracy = 59.0)))
        val later = assertNotNull(filter.fix(fix(time = 70.0, lat = 50.0002, accuracy = 59.0)))
        assertEquals(50.0002, later.latitude, 1e-9)
        assertEquals(59.0, later.accuracyMeters, 1e-6)
    }

    @Test fun repeatedFixesDoNotReportAccuracyBelowThreeMeters() {
        val filter = TrackingFilter()
        var last = assertNotNull(filter.fix(fix(accuracy = 4.0)))
        repeat(8) { index -> last = assertNotNull(filter.fix(fix(time = 2.0 + index, accuracy = 4.0))) }
        assertTrue(last.accuracyMeters >= 3.0)
    }

    @Test fun invalidInputsAreRejected() {
        for (invalid in listOf(
            fix(lat = Double.NaN), fix(lon = 181.0), fix(accuracy = 0.0), fix(speed = -0.5),
            fix(speed = Double.POSITIVE_INFINITY), fix(heading = 360.0),
            fix(source = LocationSource.deadReckoning),
        )) {
            val filter = TrackingFilter()
            assertNull(filter.fix(invalid))
            assertNotNull(filter.rejection)
        }
    }

    @Test fun antiFakeRejectsMockAndExcessiveSpeed() {
        assertNull(TrackingFilter().fix(fix(mock = true)))
        assertNull(TrackingFilter().fix(fix(speed = 220.0 / 3.6 + 0.001)))
        assertNotNull(TrackingFilter(TrackerConfig(antiFake = false)).fix(fix(mock = true)))
    }

    @Test fun outlierDoesNotMutateFilter() {
        val filter = TrackingFilter()
        filter.fix(fix())
        assertNull(filter.fix(fix(time = 2.0, lat = 50.01)))
        assertContains(assertNotNull(filter.rejection), "chi-square")
        val next = assertNotNull(filter.fix(fix(time = 2.0)))
        assertEquals(50.0, next.latitude, 1e-10)
    }

    @Test fun rejectsDuplicateAndOutOfOrderFixes() {
        val filter = TrackingFilter()
        filter.fix(fix(time = 2.0))
        assertNull(filter.fix(fix(time = 2.0)))
        assertNull(filter.fix(fix(time = 1.0)))
    }

    @Test fun reinitializesAfterGapButStillRejectsTeleport() {
        val filter = TrackingFilter()
        filter.fix(fix())
        assertNull(filter.fix(fix(time = 20.0, lat = 51.0)))
        val result = assertNotNull(filter.fix(fix(time = 20.0, lat = 50.0001)))
        assertEquals(50.0001, result.latitude, 1e-9)
    }

    @Test fun predictionHorizonIsMeasuredFromLastRealFix() {
        val filter = TrackingFilter()
        filter.fix(fix())
        assertNotNull(filter.predict(4.0, 0.0, 0.0))
        assertNotNull(filter.predict(16.0, 0.0, 0.0))
        assertNull(filter.predict(16.001, 0.0, 0.0))
    }

    @Test fun predictionsOver100MetersUncertaintyAreWithheld() {
        val filter = TrackingFilter(TrackerConfig(maxEstimateAccuracyMeters = 6.0))
        filter.fix(fix(speed = 20.0))
        assertNull(filter.predict(10.0, 0.0, 0.0))
    }

    @Test fun walkingStepsAreBoundedAndMoveExactlyStrideDistance() {
        val filter = TrackingFilter()
        filter.fix(fix())
        assertNull(filter.pedestrianSteps(2.0, 11, 0.0))
        val result = assertNotNull(filter.pedestrianSteps(2.0, 10, 90.0))
        assertEquals(7.5, distanceMeters(50.0, 30.0, result.latitude, result.longitude), 0.001)
        assertEquals(0.0, result.speedMps)
        assertNull(filter.pedestrianSteps(17.0, 1, 0.0))
    }

    @Test fun invalidDopplerAccuraciesFallBackWithoutRejectingFix() {
        val filter = TrackingFilter()
        assertNotNull(filter.fix(fix(speed = 10.0).copy(speedAccuracyMps = Double.NaN,
            bearingAccuracyDegrees = Double.POSITIVE_INFINITY)))
        assertNotNull(filter.fix(fix(time = 2.0, lat = 50.00009, speed = 10.0).copy(
            speedAccuracyMps = -10.0, bearingAccuracyDegrees = -1.0)))
    }

    @Test fun josephUpdatesRemainFiniteUnderRepeatedUpdates() {
        val filter = TrackingFilter()
        for (t in 1..300) {
            val latitude = 50.0 + 10 * (t - 1) / EARTH_RADIUS_METERS / RADIANS
            val result = assertNotNull(filter.fix(fix(time = t.toDouble(), lat = latitude, speed = 10.0)))
            assertTrue(result.accuracyMeters.isFinite() && result.accuracyMeters > 0)
            assertTrue(result.speedMps >= 0)
        }
    }

    @Test fun dateLineCrossingDoesNotLookLikeTeleport() {
        val filter = TrackingFilter()
        filter.fix(fix(lat = 0.0, lon = 179.99999, speed = 2.0, heading = 90.0))
        assertNotNull(filter.fix(fix(time = 2.0, lat = 0.0, lon = -179.99999, speed = 2.0, heading = 90.0)))
    }

    @Test fun seededDrivingTraceReducesPositionNoiseWithoutDroppingNormalFixes() {
        val filter = TrackingFilter()
        val random = Random(42)
        fun noise(): Double = sqrt(-2 * ln(random.nextDouble().coerceAtLeast(1e-12))) *
            cos(2 * PI * random.nextDouble()) * 4.0
        var rawSquared = 0.0
        var filteredSquared = 0.0
        var accepted = 0
        repeat(300) { index ->
            val trueLatitude = 50.0 + index * 15.0 / EARTH_RADIUS_METERS / RADIANS
            val rawLatitude = trueLatitude + noise() / EARTH_RADIUS_METERS / RADIANS
            val rawLongitude = 30.0 + noise() / (EARTH_RADIUS_METERS * cos(50.0 * RADIANS)) / RADIANS
            val measured = fix(time = index + 1.0, lat = rawLatitude, lon = rawLongitude,
                accuracy = 8.0, speed = 15.0).copy(speedAccuracyMps = 0.1, bearingAccuracyDegrees = 0.5)
            filter.fix(measured)?.let {
                if (index >= 10) {
                    rawSquared += distanceMeters(trueLatitude, 30.0, rawLatitude, rawLongitude).pow(2)
                    filteredSquared += distanceMeters(trueLatitude, 30.0, it.latitude, it.longitude).pow(2)
                    accepted++
                }
            }
        }
        assertTrue(accepted >= 280, "Normal Gaussian-noise fixes should not be over-rejected")
        assertTrue(filteredSquared < rawSquared * 0.8, "Smoothing must measurably reduce seeded position noise")
    }
}

class TrackThrottleTests {
    private fun point(
        time: Double = 1.0, lat: Double = 50.0, heading: Double = 0.0, speed: Double = 0.0,
        accuracy: Double = 5.0, source: LocationSource = LocationSource.gps,
    ) = TrackEstimate(lat, 30.0, accuracy, speed, heading, time, source)

    @Test fun throttleCommitsOnlyWhenRecordedIsCalled() {
        val throttle = TrackThrottle()
        assertTrue(throttle.shouldRecord(point()))
        assertTrue(throttle.shouldRecord(point()))
        throttle.recorded(point())
        assertFalse(throttle.shouldRecord(point(time = 2.0)))
        assertFalse(throttle.shouldRecord(point(time = 31.0)))
        assertTrue(throttle.shouldRecord(point(time = 121.0)))
    }

    @Test fun cityReferenceKeepsTheConfiguredGates() {
        val throttle = TrackThrottle()
        throttle.recorded(point(speed = 8.0))
        assertFalse(throttle.shouldRecord(point(time = 30.0, speed = 8.0)))
        assertTrue(throttle.shouldRecord(point(time = 31.0, speed = 8.0)))
        assertTrue(throttle.shouldRecord(point(lat = 50.0003, speed = 8.0)))
        assertFalse(throttle.shouldRecord(point(heading = 9.0, speed = 8.0)))
        assertTrue(throttle.shouldRecord(point(heading = 10.0, speed = 8.0)))
    }

    @Test fun slowSpeedUsesAWiderGateAndFastSpeedATighterOne() {
        val slow = TrackThrottle()
        slow.recorded(point())
        assertFalse(slow.shouldRecord(point(lat = 50.0003)))
        assertTrue(slow.shouldRecord(point(lat = 50.001)))
        val fast = TrackThrottle()
        fast.recorded(point(speed = 20.0))
        assertTrue(fast.shouldRecord(point(lat = 50.0003, speed = 20.0)))
        assertFalse(fast.shouldRecord(point(heading = 5.0, speed = 20.0)))
        assertTrue(fast.shouldRecord(point(heading = 6.0, speed = 20.0)))
        val crawl = TrackThrottle()
        crawl.recorded(point(speed = 2.0))
        assertFalse(crawl.shouldRecord(point(heading = 29.0, speed = 2.0)))
        assertTrue(crawl.shouldRecord(point(heading = 30.0, speed = 2.0)))
    }

    @Test fun bearingDifferenceWrapsAcrossNorth() {
        val throttle = TrackThrottle()
        throttle.recorded(point(heading = 359.0, speed = 10.0))
        assertFalse(throttle.shouldRecord(point(heading = 1.0, speed = 10.0)))
    }

    @Test fun departureBrakeAndGnssRecoveryRecordImmediately() {
        val throttle = TrackThrottle()
        throttle.recorded(point())
        assertFalse(throttle.shouldRecord(point(time = 2.0, speed = 1.5)))
        assertTrue(throttle.shouldRecord(point(time = 2.0, speed = 2.0)))
        throttle.recorded(point(speed = 20.0))
        assertFalse(throttle.shouldRecord(point(time = 2.0, speed = 16.0)))
        assertTrue(throttle.shouldRecord(point(time = 2.0, speed = 15.0)))
        throttle.recorded(point(source = LocationSource.deadReckoning))
        assertTrue(throttle.shouldRecord(point(time = 2.0, source = LocationSource.gps)))
        throttle.recorded(point())
        assertTrue(throttle.shouldRecord(point(time = 2.0, source = LocationSource.stationary)))
        throttle.recorded(point(source = LocationSource.stationary))
        assertFalse(throttle.shouldRecord(point(time = 11.0, source = LocationSource.stationary)))
    }

    @Test fun accuracyCollapseAndRecoveryRecordImmediately() {
        val throttle = TrackThrottle()
        throttle.recorded(point(accuracy = 10.0))
        assertTrue(throttle.shouldRecord(point(time = 2.0, accuracy = 50.0)))
        throttle.recorded(point(accuracy = 60.0))
        assertFalse(throttle.shouldRecord(point(time = 2.0, accuracy = 40.0)))
        assertTrue(throttle.shouldRecord(point(time = 2.0, accuracy = 20.0)))
    }

    @Test fun flushIntervalFollowsTheSameSpeedScale() {
        val throttle = TrackThrottle()
        assertEquals(30.0, throttle.flushIntervalSeconds(0.0))
        assertEquals(20.0, throttle.flushIntervalSeconds(4.0))
        assertEquals(10.0, throttle.flushIntervalSeconds(8.0))
        assertEquals(5.0, throttle.flushIntervalSeconds(20.0))
    }
}

class StopAnchorTests {
    private fun estimate(source: LocationSource = LocationSource.gps) =
        TrackEstimate(50.0, 30.0, 8.0, 0.0, 0.0, 1.0, source)
    private fun fix(time: Double = 2.0, lat: Double = 50.0, accuracy: Double = 8.0, speed: Double = 0.0,
                    source: LocationSource = LocationSource.gps) =
        LocationFix(lat, 30.0, accuracy, (time * 1000).toLong(), time, source, speed)

    @Test fun fixedAnchorDoesNotDriftAndUncertaintyGrows() {
        val stop = StopAnchor()
        stop.begin(estimate())
        repeat(30) { stop.add(fix(lat = 50.00001 + (it % 2) * 0.00001)) }
        val held = assertNotNull(stop.estimate(31.0))
        val later = assertNotNull(stop.estimate(61.0))
        assertEquals(held.latitude, later.latitude)
        assertEquals(3.0, later.accuracyMeters - held.accuracyMeters, 1e-8)
        assertTrue(held.accuracyMeters >= 8.0 / 4)
    }

    @Test fun releaseHasSpeedHysteresisAndIgnoresNetworkDisplacement() {
        val stop = StopAnchor()
        stop.begin(estimate())
        assertFalse(stop.shouldRelease(fix(speed = 1.0)))
        assertTrue(stop.shouldRelease(fix(speed = 1.001)))
        assertFalse(stop.shouldRelease(fix(lat = 51.0, speed = 20.0, source = LocationSource.network)))
        assertFalse(stop.shouldRelease(fix(lat = 50.0001)))
        assertTrue(stop.shouldRelease(fix(lat = 50.001)))
    }

    @Test fun stationaryUncertaintyGrowthIsCapped() {
        val stop = StopAnchor()
        stop.begin(estimate())
        assertEquals(23.0, assertNotNull(stop.estimate(10_000.0)).accuracyMeters, 1e-6)
    }

    @Test fun freshPreciseFixesDoNotInflateTheHeldAccuracy() {
        val stop = StopAnchor()
        stop.begin(estimate())
        val early = assertNotNull(stop.estimate(31.0, corroboratedAt = 30.0))
        val later = assertNotNull(stop.estimate(61.0, corroboratedAt = 60.0))
        assertEquals(early.accuracyMeters, later.accuracyMeters, 1e-8)
        assertTrue(later.accuracyMeters < assertNotNull(stop.estimate(61.0)).accuracyMeters)
    }

    @Test fun networkAnchorIsReplacedByFirstPreciseFix() {
        val stop = StopAnchor()
        stop.begin(estimate(LocationSource.network), network = true)
        stop.add(fix(lat = 50.0001))
        assertEquals(50.0001, assertNotNull(stop.estimate(2.0)).latitude)
    }
}

class TimeGuardTests {
    @Test fun rejectsNonMonotonicAndNonFiniteRadioEvidence() {
        val guard = TimeGuard()
        assertTrue(guard.evidence(GnssEvidence(2.0, 2000.0)))
        assertFalse(guard.evidence(GnssEvidence(1.0, 1000.0)))
        assertFalse(guard.evidence(GnssEvidence(3.0, 3000.0, meanAgcDb = Double.NaN)))
        assertEquals(2.0, guard.gnss?.monotonicSeconds)
    }

    @Test fun missingOrExpiredReferencesAreExplicitlyUnverified() {
        val guard = TimeGuard()
        assertContains(guard.flags(1.0, 1000, true), "gnssTimeUnverified")
        guard.reference = TimeReference(1000, 1.0, 20.0)
        guard.evidence(GnssEvidence(1.0, 1000.0))
        assertFalse(guard.suspect(2.0))
        assertContains(guard.flags(7.0, 7000, true), "gnssTimeUnverified")
        assertTrue(guard.needsReference(61.0))
    }

    @Test fun checksGnssAgainstMonotonicServerExtrapolation() {
        val guard = TimeGuard()
        guard.reference = TimeReference(100_000, 10.0, 50.0)
        guard.evidence(GnssEvidence(11.0, 104_000.0, 10.0))
        assertTrue(guard.suspect(12.0))
        assertContains(guard.flags(12.0, 999_999, true), "deviceTimeMismatch")
    }

    @Test fun agcAndCn0JumpLatchesInterferenceFlags() {
        val guard = TimeGuard()
        guard.evidence(GnssEvidence(1.0, null, meanAgcDb = -1.0, meanCn0DbHz = 40.0))
        guard.evidence(GnssEvidence(2.0, null, meanAgcDb = -8.0, meanCn0DbHz = 29.0))
        assertContains(guard.flags(2.0, 2000, true), "SUSPECTED_INTERFERENCE")
        assertContains(guard.flags(2.0, 2000, true), "SUSPECTED_SPOOFING")
        assertContains(guard.flags(2.0, 2000, true), "suspectedInterference")
        assertContains(guard.flags(2.0, 2000, true), "suspectedSpoofing")
    }
}
