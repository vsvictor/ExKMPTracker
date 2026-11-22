package com.exkmptracker.di

import android.app.Application
import android.content.Context
import com.exkmptracker.android.EncryptedTrackQueue
import com.exkmptracker.ports.TrackingRpcTransport
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.mp.KoinPlatform

/**
 * Start process-wide tracker Koin from the host `Application.onCreate`.
 *
 * [rpc] is the host's authenticated connection. [extra] may override the logger, config,
 * vehicle class, map matcher and edge-vision controller, including when the host already called
 * `startKoin`. A second call still applies [extra]. It does not reload the core graph, so it
 * does not open another encrypted queue and does not replace [rpc] already in the graph.
 * [extra] must not redefine the queue or [TrackingRpcTransport].
 */
fun initTrackerKoin(
    context: Context,
    rpc: TrackingRpcTransport,
    extra: List<Module> = emptyList(),
) {
    val applicationContext = context.applicationContext
    check(applicationContext is Application) { "Tracker Koin requires the Application context" }
    if (KoinPlatform.getKoinOrNull() == null) {
        startKoin { androidContext(applicationContext) }
    }
    ensureTrackerModules(
        core = listOf(
            hostTransportModule(rpc),
            trackerRpcTransportModule,
            trackerCommonModule,
            trackerPlatformModule,
        ),
        extra = extra,
    ) { koin ->
        koin.declares(TrackingRpcTransport::class) && koin.declares(EncryptedTrackQueue::class)
    }
}
