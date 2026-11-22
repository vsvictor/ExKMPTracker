@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.exkmptracker.di

import com.exkmptracker.cooperative.CooperativeTransport
import com.exkmptracker.cooperative.EdgeVisionController
import com.exkmptracker.cooperative.VehicleClass
import com.exkmptracker.ios.IosProtectedStorage
import com.exkmptracker.ios.IosTrackQueue
import com.exkmptracker.ios.IosTrackerRuntime
import com.exkmptracker.model.TrackerConfig
import com.exkmptracker.ports.MapMatcher
import com.exkmptracker.ports.PointIdGenerator
import com.exkmptracker.ports.SessionStore
import com.exkmptracker.ports.TrackQueue
import com.exkmptracker.ports.TrackerClock
import com.exkmptracker.ports.TrackerLogger
import com.exkmptracker.ports.TrackingRpcClient
import com.exkmptracker.ports.TrackingRpcTransport
import com.exkmptracker.ports.TrackingTransport
import com.exkmptracker.transport.HostRpcTrackingTransport
import com.exkmptracker.transport.RpcClientTransport
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.mp.KoinPlatform
import platform.Foundation.NSUUID

/**
 * Start process-wide tracker Koin and one runtime scope.
 *
 * Swift: `IosKoinKt.initKoinIos(client: hostRpcClient)`. A second call keeps the already registered
 * transport and does not retarget [IosTrackQueue]. Logger, config, vision and vehicle class from
 * a later call still replace the previous extras. [storage] is registered only while the queue
 * definition is absent, so a second directory cannot retarget the queue already in the graph.
 * It does not call `stopKoin`.
 */
fun initKoinIos(client: TrackingRpcClient): IosTrackerRuntime =
    openIosRuntime(client, emptyList(), storage = null, vision = null, vehicleClass = null)

fun initKoinIos(
    client: TrackingRpcClient,
    config: TrackerConfig,
    logger: TrackerLogger,
    storage: IosProtectedStorage,
): IosTrackerRuntime = openIosRuntime(
    client,
    listOf(module {
        single { config }
        single<TrackerLogger> { logger }
    }),
    storage = storage,
    vision = null,
    vehicleClass = null,
)

fun initKoinIos(
    client: TrackingRpcClient,
    vision: EdgeVisionController,
    vehicleClass: VehicleClass,
): IosTrackerRuntime = openIosRuntime(
    client,
    listOf(module {
        single<EdgeVisionController> { vision }
        single { vehicleClass }
    }),
    storage = null,
    vision = vision,
    vehicleClass = vehicleClass,
)

@HiddenFromObjC
private val iosRuntimeScopeModule = module {
    scope(TrackerQualifiers.runtime) {
        scoped<CoroutineScope>(TrackerQualifiers.runtimeScope) {
            CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(TrackerQualifiers.compute))
        } onClose { it?.cancel() }
    }
}

/** The only [TrackingTransport] registration on iOS. [trackerRpcTransportModule] is not loaded. */
@HiddenFromObjC
private fun iosClientModule(client: TrackingRpcClient) = module {
    single<TrackingRpcTransport> { RpcClientTransport(client) }
    single<TrackingTransport> { HostRpcTrackingTransport(client) } binds arrayOf(CooperativeTransport::class)
}

private fun openIosRuntime(
    client: TrackingRpcClient,
    extra: List<Module>,
    storage: IosProtectedStorage?,
    vision: EdgeVisionController?,
    vehicleClass: VehicleClass?,
): IosTrackerRuntime {
    val koinBefore = KoinPlatform.getKoinOrNull()
    val queueDeclared = koinBefore != null && koinBefore.declares(IosTrackQueue::class)
    ensureTrackerModules(
        core = listOf(trackerCommonModule, trackerPlatformModule, iosRuntimeScopeModule, iosClientModule(client)),
        extra = extra,
    ) { koin ->
        koin.declares(TrackingRpcTransport::class) && koin.declares(IosTrackQueue::class)
    }
    val koin = KoinPlatform.getKoin()
    if (storage != null && !queueDeclared) {
        koin.loadModules(listOf(module { single { storage } }), allowOverride = true)
    }
    val scope = koin.createScope(
        "exkmptracker.runtime.${NSUUID().UUIDString}",
        TrackerQualifiers.runtime,
    )
    return try {
        IosTrackerRuntime(
            scope = scope.get(TrackerQualifiers.runtimeScope),
            transport = koin.get(),
            logger = koin.get(),
            config = koin.get(),
            mapMatcher = koin.getOrNull<MapMatcher>(),
            storage = koin.get(),
            queue = koin.get<TrackQueue>(),
            sessionStore = koin.get(),
            clock = koin.get<TrackerClock>(),
            idGenerator = koin.get<PointIdGenerator>(),
            vision = vision ?: koin.getOrNull<EdgeVisionController>(),
            vehicleClass = vehicleClass ?: koin.get(),
            koinScope = scope,
        )
    } catch (error: Throwable) {
        scope.close()
        throw error
    }
}
