package com.exkmptracker.cooperative

import com.exkmptracker.model.TrackingSession
import com.exkmptracker.ports.TrackingRpcTransport
import kotlinx.serialization.json.*

class RpcCooperativeTransport(private val rpc: TrackingRpcTransport) : CooperativeTransport {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    override suspend fun syncPeers(session: TrackingSession, captureTimestampMs: Long,
        state: CooperativeState, observations: List<VisualObservation>): List<MatchedDonor> {
        require(captureTimestampMs > 0 && observations.size <= 8)
        observations.forEach { it.validate() }
        require(observations.map { it.observationId }.distinct().size == observations.size)
        val result = rpc.call("coop.sync_peers", buildJsonObject {
            put("sessionId", session.sessionId)
            put("driverId", session.identity.driverId)
            put("brandId", session.identity.brandId)
            put("captureTimestampMs", captureTimestampMs)
            put("state", json.encodeToJsonElement(state))
            put("observedPeers", json.encodeToJsonElement(observations))
        })
        val timestamp = result["captureTimestampMs"] as? JsonPrimitive
        require(timestamp?.isString == false && timestamp.longOrNull == captureTimestampMs) {
            "Cooperative response timestamp does not match request"
        }
        val donors = json.decodeFromJsonElement<List<MatchedDonor>>(
            result["matchedDonors"] ?: error("Missing matchedDonors"))
        require(donors.size <= 3 && donors.map { it.observationId }.distinct().size == donors.size &&
            donors.map { it.donorId }.distinct().size == donors.size) { "Invalid donor assignments" }
        require(donors.all { donor ->
            donor.captureTimestampMs == captureTimestampMs &&
                observations.any { it.observationId == donor.observationId && it.vehicleClass == donor.vehicleClass }
        }) { "Unsolicited donor assignment" }
        return donors
    }
}
