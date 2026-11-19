package com.exkmptracker.cooperative

import com.exkmptracker.filter.TrackingFilter
import com.exkmptracker.model.*
import com.exkmptracker.ports.TrackingRpcTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.math.*
import kotlin.test.*

class CooperativeTests {
    private fun fix(time: Double = 1.0) = LocationFix(50.0, 30.0, 15.0, 1_000,
        time, LocationSource.gps, 0.0, 0.0)
    private val observation = VisualObservation("v1", VehicleClass.CAR, 20.0, 0.0, null, 1.0)
    private fun donor(north: Double = 22.0) = MatchedDonor("v1", "opaque-peer",
        50.0 + north / 6_371_000.0 * 180 / PI, 30.0, 2.0, 0.0, 0.0, 0.9, VehicleClass.CAR, 1_100)

    @Test fun subtractionAndJosephUpdateImproveWithoutContaminatingFallback() {
        val baseline = TrackingFilter()
        val original = assertNotNull(baseline.fix(fix()))
        val branch = baseline.fork()
        val corrected = assertNotNull(branch.cooperativeFix(donor(), observation, 1.1))
        assertEquals(LocationSource.cooperative, corrected.source)
        assertTrue(corrected.latitude > original.latitude)
        assertTrue(corrected.latitude < donor().latitude)
        assertTrue(corrected.accuracyMeters < original.accuracyMeters)
        val unchanged = assertNotNull(baseline.stationary())
        assertEquals(original.latitude, unchanged.latitude, 1e-10)
        assertEquals(original.longitude, unchanged.longitude, 1e-10)
    }

    @Test fun gateAndInvalidDonorDoNotMutateFilter() {
        val filter = TrackingFilter()
        filter.fix(fix())
        val before = assertNotNull(filter.stationary())
        assertNull(filter.cooperativeFix(donor(1_000.0), observation, 1.1))
        assertContains(assertNotNull(filter.rejection), "gate")
        val after = assertNotNull(filter.stationary())
        assertEquals(before.latitude, after.latitude)
        assertNull(filter.cooperativeFix(donor().copy(trustScore = 0.69), observation, 1.1))
        assertNull(filter.cooperativeFix(donor(), observation, 0.9))
    }

    @Test fun freshDonorsSustainIndependentNavigationBeyondGnssHorizonWithErrorFloor() {
        val filter = TrackingFilter()
        filter.fix(fix())
        var estimate: TrackEstimate? = null
        repeat(400) { index ->
            val time = 1.1 + index * 0.1
            estimate = filter.cooperativeFix(donor(20.0), observation, time)
            assertNotNull(estimate)
        }
        val current = assertNotNull(estimate)
        assertTrue(current.monotonicSeconds > 40.0)
        assertTrue(current.accuracyMeters >= sqrt(4.0 + 2.27886856637673) - 0.001)
        assertNull(filter.predict(60.0, 0.0, 0.0))
    }

    @Test fun azimuthIsNorthClockwiseAndDistanceIsSubtracted() {
        val filter = TrackingFilter()
        filter.fix(fix())
        val eastObservation = observation.copy(azimuthDegrees = 90.0)
        val eastDonor = donor(0.0).copy(longitude = 30.0 + 22 / (6_371_000.0 * cos(50 * PI / 180)) * 180 / PI)
        val result = assertNotNull(filter.cooperativeFix(eastDonor, eastObservation, 1.1))
        assertTrue(result.longitude > 30.0 && result.longitude < eastDonor.longitude)
        assertEquals(50.0, result.latitude, 1e-8)
    }

    @Test fun rpcUsesExactContractAndRejectsUnsolicitedAssignments() = runTest {
        var unsolicited = false
        val transport = RpcCooperativeTransport(object : TrackingRpcTransport {
            override suspend fun call(method: String, params: JsonObject): JsonObject {
                assertEquals("coop.sync_peers", method)
                assertEquals("car", params["state"]!!.jsonObject["vehicleClass"]!!.jsonPrimitive.content)
                assertEquals(1_100, params["captureTimestampMs"]!!.jsonPrimitive.int)
                return buildJsonObject {
                    put("captureTimestampMs", 1_100)
                    putJsonArray("matchedDonors") {
                        add(Json.encodeToJsonElement(donor().copy(observationId = if (unsolicited) "alien" else "v1")))
                    }
                }
            }
            override suspend fun notify(method: String, params: JsonObject) = error("Notifications are forbidden")
        })
        val session = TrackingSession.fromConfirmedShift("s", "d", "b")
        val state = CooperativeState(50.0, 30.0, 10.0, 0.0, 0.0, 0.8, VehicleClass.CAR, emptyList())
        assertEquals(1, transport.syncPeers(session, 1_100, state, listOf(observation)).size)
        unsolicited = true
        assertFailsWith<IllegalArgumentException> { transport.syncPeers(session, 1_100, state, listOf(observation)) }
    }
}
