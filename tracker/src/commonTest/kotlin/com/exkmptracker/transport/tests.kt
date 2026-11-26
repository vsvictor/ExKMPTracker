package com.exkmptracker.transport

import com.exkmptracker.model.LocationSource
import com.exkmptracker.model.TrackPoint
import com.exkmptracker.model.TrackingSession
import com.exkmptracker.ports.TrackingRpcTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class RpcTrackingTransportTests {
    private val session = TrackingSession.fromConfirmedShift("confirmed", "driver", "brand")
    private val point = TrackPoint("00000000000000000000000000000001", null, null, null,
        50.0, 30.0, 5.0, 2.0, 90.0, 1000, 1000, LocationSource.deadReckoning, false, listOf("predicted"))

    private class Rpc(val result: JsonObject) : TrackingRpcTransport {
        val requests = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun call(method: String, params: JsonObject): JsonObject {
            requests.add(method to params)
            return result
        }
        override suspend fun notify(method: String, params: JsonObject) {
            error("Durable delivery must never use notifications")
        }
    }

    @Test fun hostConnectionReceivesBusinessParametersWithoutCredentialsOrEnvelope() = runTest {
        val rpc = Rpc(buildJsonObject { putJsonArray("acceptedIds") { add(point.id) } })
        assertEquals(listOf(point.id), RpcTrackingTransport(rpc).ingest(session, listOf(point)))
        val (method, params) = rpc.requests.single()
        assertEquals("tracking.ingest", method)
        assertEquals(setOf("sessionId", "driverId", "brandId", "points"), params.keys)
        assertEquals("confirmed", params["sessionId"]!!.jsonPrimitive.content)
        assertEquals("driver", params["driverId"]!!.jsonPrimitive.content)
        assertEquals("brand", params["brandId"]!!.jsonPrimitive.content)
        val encoded = params["points"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, encoded["rawLatitude"])
        assertEquals("deadReckoning", encoded["source"]!!.jsonPrimitive.content)
        assertFalse("trackIncrementMeters" in encoded)
        assertFalse("phantomIncrementMeters" in encoded)
    }

    @Test fun preservesMileageIncrementsOnUploadAndRetry() = runTest {
        val measured = point.copy(trackIncrementMeters = 12.5, phantomIncrementMeters = 3.0)
        val rpc = Rpc(buildJsonObject { putJsonArray("acceptedIds") { add(point.id) } })
        val transport = RpcTrackingTransport(rpc)
        transport.ingest(session, listOf(measured))
        transport.ingest(session, listOf(measured))
        val first = rpc.requests.first().second["points"]!!.jsonArray.single().jsonObject
        val second = rpc.requests.last().second["points"]!!.jsonArray.single().jsonObject
        assertEquals(12.5, first["trackIncrementMeters"]!!.jsonPrimitive.double)
        assertEquals(3.0, first["phantomIncrementMeters"]!!.jsonPrimitive.double)
        assertEquals(first, second)
        assertEquals(point, Json.decodeFromString<TrackPoint>(Json.encodeToString(TrackPoint.serializer(), point)))
    }

    @Test fun acceptsPartialAcknowledgementsButRejectsForeignOrDuplicateIds() = runTest {
        for (ids in listOf(listOf("foreign"), listOf(point.id, point.id))) {
            val rpc = Rpc(buildJsonObject { put("acceptedIds", JsonArray(ids.map(::JsonPrimitive))) })
            assertFailsWith<IllegalArgumentException> { RpcTrackingTransport(rpc).ingest(session, listOf(point)) }
        }
        val rpc = Rpc(buildJsonObject { put("acceptedIds", JsonArray(emptyList())) })
        assertEquals(emptyList(), RpcTrackingTransport(rpc).ingest(session, listOf(point)))
    }

    @Test fun validatesTimeAndBoundsBeforeTrustingResult() = runTest {
        for (value in listOf(JsonPrimitive("1000"), JsonPrimitive(0), JsonPrimitive(-1), JsonNull)) {
            val rpc = Rpc(buildJsonObject { put("serverTimeMs", value) })
            assertFailsWith<IllegalArgumentException> { RpcTrackingTransport(rpc).serverTime(session) }
        }
        val rpc = Rpc(buildJsonObject { put("serverTimeMs", 1000) })
        assertEquals(1000, RpcTrackingTransport(rpc).serverTime(session).serverTimeMs)
        assertEquals("serverTime", rpc.requests.single().first)
        assertFailsWith<IllegalArgumentException> { RpcTrackingTransport(rpc).ingest(session, emptyList()) }
        assertFailsWith<IllegalArgumentException> { RpcTrackingTransport(rpc).ingest(session, List(101) { point }) }
        assertFailsWith<IllegalArgumentException> { RpcTrackingTransport(rpc).ingest(session, listOf(point, point)) }
    }

    @Test fun cancellationAndAuthErrorsPropagateToBlocWithoutFallbackConnection() = runTest {
        val rpc = object : TrackingRpcTransport {
            override suspend fun call(method: String, params: JsonObject): JsonObject =
                throw CancellationException("Host disconnected")
            override suspend fun notify(method: String, params: JsonObject) = Unit
        }
        assertFailsWith<CancellationException> { RpcTrackingTransport(rpc).serverTime(session) }
    }
}
