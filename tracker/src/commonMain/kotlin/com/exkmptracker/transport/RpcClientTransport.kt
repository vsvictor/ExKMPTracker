package com.exkmptracker.transport

import com.exkmptracker.ports.TrackingRpcClient
import com.exkmptracker.ports.TrackingRpcTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Bridges a Swift [TrackingRpcClient] to the suspend RPC port. Tokens are not logged. */
class RpcClientTransport(private val client: TrackingRpcClient) : TrackingRpcTransport {
    override suspend fun call(method: String, params: JsonObject): JsonObject {
        val response = CompletableDeferred<String>()
        val handle = client.call(method, params.toString()) { result, failure ->
            when {
                failure != null -> response.completeExceptionally(
                    IllegalStateException("Host RPC failed (code ${failure.code})"),
                )
                result != null -> response.complete(result)
                else -> response.completeExceptionally(IllegalStateException("Host RPC returned no result"))
            }
        }
        try {
            return Json.parseToJsonElement(response.await()) as? JsonObject
                ?: error("Host RPC result must be an object")
        } catch (cancelled: CancellationException) {
            handle.cancel()
            throw cancelled
        } finally {
            response.cancel()
        }
    }

    override suspend fun notify(method: String, params: JsonObject) = client.notify(method, params.toString())
}
