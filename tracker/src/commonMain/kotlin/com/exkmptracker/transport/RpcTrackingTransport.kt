package com.exkmptracker.transport

import com.exkmptracker.model.TrackPoint
import com.exkmptracker.model.TrackingSession
import com.exkmptracker.ports.ServerTime
import com.exkmptracker.ports.TrackingRpcTransport
import com.exkmptracker.ports.TrackingTransport
import com.exkmptracker.cooperative.*
import kotlinx.serialization.json.*

/** No sockets or HTTP client: every operation uses the host-owned RPC connection. */
class RpcTrackingTransport(private val rpc: TrackingRpcTransport) : TrackingTransport,
    CooperativeTransport by RpcCooperativeTransport(rpc) {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    override suspend fun serverTime(session: TrackingSession): ServerTime {
        val result = rpc.call("serverTime", buildJsonObject { put("sessionId", session.sessionId) })
        val value = result["serverTimeMs"] as? JsonPrimitive
        val time = value?.longOrNull
        require(value?.isString == false && time != null && time > 0) { "Invalid serverTime result" }
        return ServerTime(time)
    }

    override suspend fun ingest(session: TrackingSession, points: List<TrackPoint>): List<String> {
        require(points.size in 1..100) { "Tracking batches require 1..100 points" }
        val submitted = points.map { it.id }
        require(submitted.distinct().size == submitted.size &&
            submitted.all { it.matches(Regex("[0-9a-f]{32}")) }) { "Invalid or duplicate point IDs" }
        val result = rpc.call("tracking.ingest", buildJsonObject {
            put("sessionId", session.sessionId)
            put("driverId", session.identity.driverId)
            put("brandId", session.identity.brandId)
            put("points", json.encodeToJsonElement(points))
        })
        val values = result["acceptedIds"] as? JsonArray ?: error("Missing acceptedIds")
        val accepted = values.map {
            val id = it as? JsonPrimitive ?: error("Invalid accepted ID")
            require(id.isString) { "Accepted ID must be a string" }
            id.content
        }
        require(accepted.distinct().size == accepted.size && accepted.all { it in submitted }) {
            "Invalid acceptedIds response"
        }
        return accepted
    }
}
