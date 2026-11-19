package com.exkmptracker.ios

import com.exkmptracker.model.TrackPoint
import com.exkmptracker.model.TrackingSession
import com.exkmptracker.ports.ServerTime
import com.exkmptracker.ports.TrackingTransport
import com.exkmptracker.transport.JsonRpcTrackingTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

/**
 * Optional legacy HTTP transport using Ktor's Darwin engine. The caller owns its lifetime and
 * must call [close] only after the runtime has stopped. Authentication secrets are not persisted.
 */
class IosJsonRpcTrackingTransport @Throws(Exception::class) constructor(
    endpoint: String,
    bearerToken: String? = null,
) : TrackingTransport {
    private val authorization = bearerToken?.let {
        require(it.isNotBlank()) { "Bearer token must not be blank" }
        "Bearer $it"
    }

    private val client = HttpClient(Darwin) {
        defaultRequest {
            authorization?.let { header(HttpHeaders.Authorization, it) }
        }
    }

    private val delegate = try {
        JsonRpcTrackingTransport(endpoint, client)
    } catch (error: Throwable) {
        client.close()
        throw error
    }

    override suspend fun serverTime(session: TrackingSession): ServerTime =
        delegate.serverTime(session)

    override suspend fun ingest(session: TrackingSession, points: List<TrackPoint>): List<String> =
        delegate.ingest(session, points)

    fun close() {
        client.close()
    }
}
