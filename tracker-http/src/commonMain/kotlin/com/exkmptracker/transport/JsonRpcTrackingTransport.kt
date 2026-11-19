package com.exkmptracker.transport

import com.exkmptracker.model.*
import com.exkmptracker.ports.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Optional legacy HTTP adapter. CoopNav hosts should use [RpcTrackingTransport] instead. */
class JsonRpcTrackingTransport(
    private val endpoint: String,
    private val client: HttpClient,
) : TrackingTransport {
    private val lock = Mutex()
    private var nextId = 0L
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    init {
        val url = Url(endpoint)
        require(url.protocol == URLProtocol.HTTPS) { "Tracking endpoint must use HTTPS" }
        require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) { "Credentials must not be embedded in the URL" }
    }

    override suspend fun serverTime(session: TrackingSession): ServerTime {
        val result = call("serverTime", buildJsonObject { put("sessionId", session.sessionId) })
        val value = result["serverTimeMs"] as? JsonPrimitive
        val time = value?.longOrNull
        require(value?.isString == false && time != null && time > 0) { "Invalid serverTime result" }
        return ServerTime(time)
    }

    override suspend fun ingest(session: TrackingSession, points: List<TrackPoint>): List<String> {
        require(points.size in 1..100)
        val result = call("tracking.ingest", buildJsonObject {
            put("sessionId", session.sessionId)
            put("driverId", session.identity.driverId)
            put("brandId", session.identity.brandId)
            put("points", json.encodeToJsonElement(points))
        })
        val accepted = result["acceptedIds"] as? JsonArray ?: error("Missing acceptedIds")
        return accepted.map {
            val id = it as? JsonPrimitive ?: error("Invalid accepted ID")
            require(id.isString) { "Accepted ID must be a string" }
            id.content
        }
    }

    private suspend fun call(method: String, params: JsonObject): JsonObject {
        val id = lock.withLock { ++nextId }
        val response = client.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString())
        }
        require(response.status.isSuccess()) { "JSON-RPC HTTP ${response.status.value}" }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val version = body["jsonrpc"] as? JsonPrimitive
        val responseId = body["id"] as? JsonPrimitive
        require(version?.isString == true && version.content == "2.0" &&
            responseId?.isString == false && responseId.longOrNull == id) { "Invalid JSON-RPC response envelope" }
        // Server error data may contain credentials; do not include it in logs.
        check(body["error"] == null) { "JSON-RPC $method failed (code ${body["error"]?.jsonObject?.get("code")})" }
        return body["result"] as? JsonObject ?: error("Missing JSON-RPC result")
    }
}
