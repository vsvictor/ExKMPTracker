@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.exkmptracker.di

import com.exkmptracker.CoopNavTracker
import com.exkmptracker.cooperative.CooperativeTransport
import com.exkmptracker.ports.TrackingRpcTransport
import com.exkmptracker.ports.TrackingTransport
import com.exkmptracker.transport.RpcTrackingTransport
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Shared tracker bindings. Platform runtimes build [com.exkmptracker.bloc.TrackerBloc] inside the
 * runtime scope; [CoopNavTracker] stays a factory so a process singleton cannot own session state.
 * The factory builds its scope from the root compute dispatcher. iOS does not load
 * [trackerRpcTransportModule]; it binds [com.exkmptracker.transport.HostRpcTrackingTransport] once.
 */
@HiddenFromObjC
val trackerCommonModule = module {
    factory {
        CoopNavTracker(
            scope = CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(TrackerQualifiers.compute)),
            clock = get(),
            queue = get(),
            rpc = get(),
            idGenerator = get(),
            logger = get(),
            sensors = get(),
            config = get(),
            mapMatcher = getOrNull(),
            vision = getOrNull(),
            vehicleClass = get(),
        )
    }
}

/**
 * Android and the graph tests load this beside [hostTransportModule]. iOS must not: its client
 * module is the only [TrackingTransport] registration.
 */
@HiddenFromObjC
val trackerRpcTransportModule = module {
    singleOf(::RpcTrackingTransport) {
        bind<TrackingTransport>()
        bind<CooperativeTransport>()
    }
}

/** Host-owned RPC connection. Loaded only by `initTrackerKoin`. */
@HiddenFromObjC
fun hostTransportModule(rpc: TrackingRpcTransport) = module {
    single<TrackingRpcTransport> { rpc }
}
