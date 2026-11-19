package com.exkmptracker.transport.host

import com.exkmptracker.model.TrackingSession
import com.exkmptracker.ports.*
import com.exkmptracker.transport.HostRpcTrackingTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HostRpcTrackingTransportTests {
    private val session = TrackingSession.fromConfirmedShift("s", "d", "b")

    private class Client : TrackingRpcClient {
        var completion: ((String?, TrackingRpcFailure?) -> Unit)? = null
        var cancelled = false
        var immediate: String? = null
        override fun call(method: String, paramsJson: String,
            completion: (String?, TrackingRpcFailure?) -> Unit): TrackingRpcCall {
            this.completion = completion
            immediate?.let { completion(it, null) }
            return object : TrackingRpcCall { override fun cancel() { cancelled = true } }
        }
        override fun notify(method: String, paramsJson: String) = Unit
    }

    @Test fun bridgesSynchronousAndAsynchronousCallbacks() = runTest {
        val client = Client().apply { immediate = """{"serverTimeMs":1000}""" }
        val transport = HostRpcTrackingTransport(client)
        assertEquals(1000, transport.serverTime(session).serverTimeMs)
        client.immediate = null
        val result = async { transport.serverTime(session) }
        runCurrent()
        client.completion!!("""{"serverTimeMs":2000}""", null)
        client.completion!!("""{"serverTimeMs":3000}""", null)
        assertEquals(2000, result.await().serverTimeMs)
    }

    @Test fun cancellationReachesHostRequestAndLateCallbacksAreIgnored() = runTest {
        val client = Client()
        val pending = launch { HostRpcTrackingTransport(client).serverTime(session) }
        runCurrent()
        pending.cancelAndJoin()
        assertTrue(client.cancelled)
        client.completion!!("""{"serverTimeMs":1000}""", null)
    }

    @Test fun preservesAuthErrorCodeWithoutServerErrorData() = runTest {
        val client = Client()
        val pending = async {
            assertFailsWith<IllegalStateException> { HostRpcTrackingTransport(client).serverTime(session) }
        }
        runCurrent()
        client.completion!!(null, TrackingRpcFailure(-32003))
        assertEquals("Host RPC failed (code -32003)", pending.await().message)
    }
}
