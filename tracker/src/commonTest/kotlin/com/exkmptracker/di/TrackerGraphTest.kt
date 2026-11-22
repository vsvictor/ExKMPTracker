@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.exkmptracker.di

import com.exkmptracker.CoopNavTracker
import com.exkmptracker.bloc.TrackerEvent
import com.exkmptracker.cooperative.VehicleClass
import com.exkmptracker.model.TrackPoint
import com.exkmptracker.model.TrackerConfig
import com.exkmptracker.model.TrackingIdentity
import com.exkmptracker.model.TrackingSession
import com.exkmptracker.ports.PlatformSensorProvider
import com.exkmptracker.ports.PointIdGenerator
import com.exkmptracker.ports.SessionStore
import com.exkmptracker.ports.TrackQueue
import com.exkmptracker.ports.TrackQueueStorage
import com.exkmptracker.ports.TrackerClock
import com.exkmptracker.ports.TrackerLogger
import com.exkmptracker.ports.TrackingRpcTransport
import com.exkmptracker.ports.TrackingTransport
import com.exkmptracker.cooperative.CooperativeTransport
import com.exkmptracker.transport.RpcTrackingTransport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import org.koin.test.KoinTest
import org.koin.test.get

@OptIn(ExperimentalCoroutinesApi::class)
class TrackerGraphTest : KoinTest {
    @AfterTest
    fun tearDown() {
        if (KoinPlatform.getKoinOrNull() != null) stopKoin()
    }

    @Test
    fun transportAndQueueAreSingleInstances() {
        startGraph(hostTransportModule(FakeRpc()))
        val transport = get<TrackingTransport>()
        assertTrue(transport is RpcTrackingTransport)
        assertSame(transport, get<CooperativeTransport>())
        val queue = get<TrackQueue>()
        assertSame(queue, get<TrackQueueStorage>())
        assertSame(queue, get<TrackQueue>())
        assertEquals(1, MemoryQueue.created)
    }

    @Test
    fun missingHostTransportFailsResolution() {
        startGraph()
        val failure = assertFails { get<TrackingTransport>() }
        assertTrue(failure.hasCause<NoDefinitionFoundException>(), failure.stackTraceToString())
    }

    @Test
    fun platformErrorIsLoggedOnTheTestDispatcher() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = FakeLogger()
        startGraph(hostTransportModule(FakeRpc()), dispatcher = dispatcher, logger = logger)
        val tracker = get<CoopNavTracker>()
        tracker.dispatch(TrackerEvent.PlatformError("sensor failed"))
        advanceUntilIdle()
        assertEquals(listOf("sensor failed"), logger.messages)
    }

    @Test
    fun secondLoadKeepsTheFirstTransport() {
        val first = FakeRpc()
        val second = FakeRpc()
        loadTrackerModules(listOf(hostTransportModule(first), trackerRpcTransportModule, trackerCommonModule, testPlatformModule()))
        assertSame(first, get<TrackingRpcTransport>())
        loadTrackerModules(listOf(hostTransportModule(second), trackerRpcTransportModule, trackerCommonModule, testPlatformModule()))
        assertSame(first, get<TrackingRpcTransport>())
    }

    @Test
    fun extraLoggerIsAppliedWhenKoinIsAlreadyStarted() {
        val first = FakeRpc()
        val second = FakeRpc()
        val fromCore = FakeLogger()
        val fromExtra = FakeLogger()
        val replacement = FakeLogger()
        startKoin { modules(module { single { "host-already-started" } }) }
        ensureTrackerModules(
            core = listOf(
                hostTransportModule(first),
                trackerRpcTransportModule,
                trackerCommonModule,
                testPlatformModule(logger = fromCore),
            ),
            extra = listOf(module { single<TrackerLogger> { fromExtra } }),
        )
        assertSame(first, get<TrackingRpcTransport>())
        assertSame(fromExtra, get<TrackerLogger>())
        ensureTrackerModules(
            core = listOf(
                hostTransportModule(second),
                trackerRpcTransportModule,
                trackerCommonModule,
                testPlatformModule(logger = replacement),
            ),
            extra = listOf(module { single<TrackerLogger> { replacement } }),
        )
        assertSame(first, get<TrackingRpcTransport>())
        assertSame(replacement, get<TrackerLogger>())
        get<CoopNavTracker>()
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun trackingTransportIsNotBoundByTheCommonModule() {
        assertTrue(TrackingTransport::class !in trackerCommonModule.declaredTypes())
        assertTrue(CooperativeTransport::class !in trackerCommonModule.declaredTypes())
        val rpcTypes = trackerRpcTransportModule.declaredTypes()
        assertEquals(1, rpcTypes.count { it == TrackingTransport::class })
        assertTrue(CooperativeTransport::class in rpcTypes)
    }

    private fun startGraph(
        vararg extra: Module,
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
        logger: TrackerLogger = FakeLogger(),
    ) {
        MemoryQueue.created = 0
        startKoin {
            modules(listOf(trackerRpcTransportModule, trackerCommonModule, testPlatformModule(dispatcher, logger)) + extra)
        }
    }
}

private class FakeRpc : TrackingRpcTransport {
    override suspend fun call(method: String, params: JsonObject): JsonObject = JsonObject(emptyMap())
    override suspend fun notify(method: String, params: JsonObject) = Unit
}

private class MemoryQueue : TrackQueue {
    init { created += 1 }

    override suspend fun append(identity: TrackingIdentity, point: TrackPoint) = Unit
    override suspend fun peek(identity: TrackingIdentity, limit: Int): List<TrackPoint> = emptyList()
    override suspend fun acknowledge(identity: TrackingIdentity, ids: List<String>) = Unit
    override suspend fun size(identity: TrackingIdentity): Long = 0

    companion object { var created = 0 }
}

private class MemorySessionStore : SessionStore {
    private var saved: TrackingSession? = null
    override suspend fun load(): TrackingSession? = saved
    override suspend fun save(session: TrackingSession) { saved = session }
    override suspend fun clear() { saved = null }
}

private class FakeClock : TrackerClock {
    override fun wallTimeMs(): Long = 0
    override fun monotonicSeconds(): Double = 0.0
}

private class FakeLogger : TrackerLogger {
    val messages = mutableListOf<String>()
    override fun error(message: String, cause: Throwable?) { messages += message }
}

private class FakeSensors : PlatformSensorProvider {
    override fun setStationary(stationary: Boolean) = Unit
}

private class FakeIds : PointIdGenerator {
    override fun newId(): String = "0123456789abcdef0123456789abcdef"
}

/** Test platform only. Production [trackerPlatformModule] is intentionally not included. */
internal fun trackerGraphUnderTest(): Module = module {
    includes(trackerRpcTransportModule, trackerCommonModule, testPlatformModule())
}

private fun testPlatformModule(
    dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
    logger: TrackerLogger = FakeLogger(),
    queue: MemoryQueue = MemoryQueue(),
): Module = module {
    single<CoroutineDispatcher>(TrackerQualifiers.main) { dispatcher }
    single<CoroutineDispatcher>(TrackerQualifiers.io) { dispatcher }
    single<CoroutineDispatcher>(TrackerQualifiers.compute) { dispatcher }
    single<TrackerClock> { FakeClock() }
    single<PointIdGenerator> { FakeIds() }
    single<TrackerLogger> { logger }
    single<TrackQueue> { queue } binds arrayOf(TrackQueueStorage::class)
    single<SessionStore> { MemorySessionStore() }
    single<PlatformSensorProvider> { FakeSensors() }
    single { TrackerConfig() }
    single { VehicleClass.CAR }
}

@OptIn(KoinInternalApi::class)
private fun Module.declaredTypes(): List<KClass<*>> = mappings.values.distinct().flatMap { factory ->
    val bean = factory.beanDefinition
    listOf(bean.primaryType) + bean.secondaryTypes
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return true
        current = current.cause
    }
    return false
}
