package com.exkmptracker.transport

import com.exkmptracker.model.TrackPoint
import com.exkmptracker.model.TrackingSession
import com.exkmptracker.ports.*
import com.exkmptracker.cooperative.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*

/** Swift-friendly host bridge; it never creates, owns or closes a network connection. */
class HostRpcTrackingTransport(client: TrackingRpcClient) : TrackingTransport, CooperativeTransport {
    private val delegate = RpcTrackingTransport(object : TrackingRpcTransport {
        override suspend fun call(method: String, params: JsonObject): JsonObject {
            val response = CompletableDeferred<String>()
            val handle = client.call(method, params.toString()) { result, failure ->
                when {
                    failure != null -> response.completeExceptionally(
                        IllegalStateException("Host RPC failed (code ${failure.code})"))
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
    })

    override suspend fun serverTime(session: TrackingSession): ServerTime = delegate.serverTime(session)
    override suspend fun ingest(session: TrackingSession, points: List<TrackPoint>): List<String> =
        delegate.ingest(session, points)

    override suspend fun syncPeers(session: TrackingSession, captureTimestampMs: Long,
        state: CooperativeState, observations: List<VisualObservation>): List<MatchedDonor> =
        delegate.syncPeers(session, captureTimestampMs, state, observations)
}
