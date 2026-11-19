package com.exkmptracker

import com.exkmptracker.bloc.TrackerBloc
import com.exkmptracker.bloc.TrackerEvent
import com.exkmptracker.bloc.TrackerState
import com.exkmptracker.model.TrackerConfig
import com.exkmptracker.ports.*
import com.exkmptracker.transport.RpcTrackingTransport
import com.exkmptracker.cooperative.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** The BLoC is the sole owner of tracking state and section transitions. */
class CoopNavTracker(
    scope: CoroutineScope,
    clock: TrackerClock,
    queue: TrackQueueStorage,
    rpc: TrackingRpcTransport,
    idGenerator: PointIdGenerator,
    logger: TrackerLogger,
    sensors: PlatformSensorProvider,
    config: TrackerConfig = TrackerConfig(),
    mapMatcher: MapMatcher? = null,
    vision: EdgeVisionController? = null,
    vehicleClass: VehicleClass = VehicleClass.CAR,
) {
    private val bloc = TrackerBloc(scope, clock, queue, RpcTrackingTransport(rpc),
        idGenerator, logger, sensors, config, mapMatcher, RpcCooperativeTransport(rpc), vision, vehicleClass)

    val state: StateFlow<TrackerState> get() = bloc.state

    @Throws(IllegalStateException::class)
    fun dispatch(event: TrackerEvent) = bloc.dispatch(event)

    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    suspend fun section(number: Int, enabled: Boolean) = bloc.section(number, enabled)

    suspend fun shutdown() = bloc.shutdown()
}
