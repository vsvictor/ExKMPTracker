package com.exkmptracker.ports

import kotlinx.serialization.json.JsonObject

/**
 * An adapter over the host's existing authenticated JSON-RPC-2-Client-KMP connection.
 * [call] returns the decoded result, not a JSON-RPC envelope, and throws on RPC errors.
 * The host owns authentication, token refresh, request IDs, TLS, reconnects and cancellation.
 */
interface TrackingRpcTransport {
    suspend fun call(method: String, params: JsonObject): JsonObject
    suspend fun notify(method: String, params: JsonObject)
}
