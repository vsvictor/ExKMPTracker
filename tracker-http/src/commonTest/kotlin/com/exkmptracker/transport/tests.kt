package com.exkmptracker.transport

import com.exkmptracker.model.*
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class JsonRpcTrackingTransportTests {
    private val session = TrackingSession.fromConfirmedShift("session", "driver", "brand")

    @Test fun httpsIsMandatory() {
        val client = HttpClient(MockEngine { error("Must not connect") })
        try {
            assertFailsWith<IllegalArgumentException> { JsonRpcTrackingTransport("http://example.test/rpc", client) }
        } finally { client.close() }
    }

    @Test fun serverTimeValidatesResponseIdAndReturnsServerTimestamp() = runTest {
        val client = HttpClient(MockEngine {
            respond("""{"jsonrpc":"2.0","id":1,"result":{"serverTimeMs":123456789}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            assertEquals(123456789, JsonRpcTrackingTransport("https://example.test/rpc", client)
                .serverTime(session).serverTimeMs)
        } finally { client.close() }
    }

    @Test fun mismatchedResponseIsRejected() = runTest {
        val client = HttpClient(MockEngine { respond("""{"jsonrpc":"2.0","id":99,"result":{"serverTimeMs":1}}""") })
        try {
            assertFailsWith<IllegalArgumentException> {
                JsonRpcTrackingTransport("https://example.test/rpc", client).serverTime(session)
            }
        } finally { client.close() }
    }

    @Test fun stringIdCannotImpersonateNumericRequestId() = runTest {
        val client = HttpClient(MockEngine { respond("""{"jsonrpc":"2.0","id":"1","result":{"serverTimeMs":1}}""") })
        try {
            assertFailsWith<IllegalArgumentException> {
                JsonRpcTrackingTransport("https://example.test/rpc", client).serverTime(session)
            }
        } finally { client.close() }
    }

    @Test fun pointSchemaContainsNullRawFieldsForPredictionsAndIdentityOnEveryBatch() = runTest {
        var request: JsonObject? = null
        val client = HttpClient(MockEngine {
            request = Json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
            respond("""{"jsonrpc":"2.0","id":1,"result":{"acceptedIds":["00000000000000000000000000000001"]}}""")
        })
        try {
            val point = TrackPoint("00000000000000000000000000000001", null, null, null,
                50.0, 30.0, 10.0, 5.0, 10.0, 1000, 1000, LocationSource.deadReckoning, false, listOf("predicted"))
            val ids = JsonRpcTrackingTransport("https://example.test/rpc", client).ingest(session, listOf(point))
            assertEquals(listOf(point.id), ids)
            val params = assertNotNull(request)["params"]!!.jsonObject
            assertEquals("driver", params["driverId"]!!.jsonPrimitive.content)
            assertEquals("brand", params["brandId"]!!.jsonPrimitive.content)
            val encoded = params["points"]!!.jsonArray.single().jsonObject
            assertEquals(JsonNull, encoded["rawLatitude"])
            assertEquals("deadReckoning", encoded["source"]!!.jsonPrimitive.content)
        } finally { client.close() }
    }
}
