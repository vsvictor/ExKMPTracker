package com.exkmptracker.di

import android.app.Application
import android.content.Context
import com.exkmptracker.android.AndroidActivityRecognition
import com.exkmptracker.android.AndroidGnssMonitor
import com.exkmptracker.android.AndroidLocationSource
import com.exkmptracker.android.AndroidSensorController
import com.exkmptracker.android.AndroidTrackerClock
import com.exkmptracker.android.AndroidTrackerLogger
import com.exkmptracker.android.AndroidTrackerRuntime
import com.exkmptracker.android.EncryptedSessionStore
import com.exkmptracker.android.EncryptedTrackQueue
import com.exkmptracker.android.SecurePointIdGenerator
import com.exkmptracker.android.TrackerEventBus
import com.exkmptracker.android.androidStationaryControl
import com.exkmptracker.bloc.TrackerBloc
import com.exkmptracker.cooperative.VehicleClass
import com.exkmptracker.model.TrackerConfig
import com.exkmptracker.ports.PointIdGenerator
import com.exkmptracker.ports.SessionStore
import com.exkmptracker.ports.TrackQueue
import com.exkmptracker.ports.TrackQueueStorage
import com.exkmptracker.ports.TrackerClock
import com.exkmptracker.ports.TrackerLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.dsl.override

actual val trackerPlatformModule = module {
    single<Context> {
        val application = get<Application>()
        val context = application.applicationContext
        check(context is Application) { "Tracker Koin requires the Application context" }
        context
    }.override()
    single<CoroutineDispatcher>(TrackerQualifiers.main) { Dispatchers.Main.immediate }
    single<CoroutineDispatcher>(TrackerQualifiers.io) { Dispatchers.IO }
    single<CoroutineDispatcher>(TrackerQualifiers.compute) { Dispatchers.Default }
    singleOf(::AndroidTrackerClock) { bind<TrackerClock>() }
    singleOf(::SecurePointIdGenerator) { bind<PointIdGenerator>() }
    single<TrackerLogger> { AndroidTrackerLogger() }
    single { TrackerConfig() }
    single { VehicleClass.CAR }
    single<EncryptedTrackQueue> {
        EncryptedTrackQueue(get(), get(TrackerQualifiers.io))
    } onClose { it?.close() } binds arrayOf(TrackQueue::class, TrackQueueStorage::class)
    single<SessionStore> { EncryptedSessionStore(get(), get(TrackerQualifiers.io)) }
    scope(TrackerQualifiers.runtime) {
        scopedOf(::TrackerEventBus)
        scoped<CoroutineScope>(TrackerQualifiers.runtimeScope) {
            CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(TrackerQualifiers.main))
        } onClose { it?.cancel() }
        scopedOf(::AndroidSensorController) onClose { it?.close() }
        scopedOf(::AndroidLocationSource) onClose { it?.close() }
        scopedOf(::AndroidGnssMonitor) onClose { it?.close() }
        scopedOf(::AndroidActivityRecognition) onClose { it?.close() }
        scoped {
            val sensors = get<AndroidSensorController>()
            val location = get<AndroidLocationSource>()
            val gnss = get<AndroidGnssMonitor>()
            val bloc = TrackerBloc(
                scope = get(TrackerQualifiers.runtimeScope),
                clock = get(),
                queue = get(),
                transport = get(),
                idGenerator = get(),
                logger = get(),
                sensors = androidStationaryControl(location, gnss, sensors),
                config = get(),
                mapMatcher = getOrNull(),
                cooperativeTransport = getOrNull(),
                vision = getOrNull(),
                vehicleClass = get(),
            )
            get<TrackerEventBus>().attach(bloc)
            bloc
        }
        scoped {
            AndroidTrackerRuntime(
                scope = get(TrackerQualifiers.runtimeScope),
                logger = get(),
                sessionStore = get(),
                queue = get(),
                events = get(),
                blocInstance = get(),
                sensors = get(),
                location = get(),
                gnss = get(),
                activityRecognition = get(),
                mainDispatcher = get(TrackerQualifiers.main),
                closeQueueOnShutdown = false,
            )
        }
    }
}
