package com.exkmptracker.di

import com.exkmptracker.cooperative.VehicleClass
import com.exkmptracker.model.TrackerConfig
import com.exkmptracker.ports.PlatformSensorProvider
import com.exkmptracker.ports.PointIdGenerator
import com.exkmptracker.ports.SessionStore
import com.exkmptracker.ports.TrackQueue
import com.exkmptracker.ports.TrackQueueStorage
import com.exkmptracker.ports.TrackerClock
import com.exkmptracker.ports.TrackerLogger
import org.koin.core.module.Module
import org.koin.dsl.module

private const val JVM_PLATFORM = "JVM target has no native tracker platform"

actual val trackerPlatformModule: Module = module {
    single<TrackerClock> { throw UnsupportedOperationException(JVM_PLATFORM) }
    single<PointIdGenerator> { throw UnsupportedOperationException(JVM_PLATFORM) }
    single<TrackerLogger> { throw UnsupportedOperationException(JVM_PLATFORM) }
    single<TrackQueue> { throw UnsupportedOperationException(JVM_PLATFORM) }
    single<TrackQueueStorage> { throw UnsupportedOperationException(JVM_PLATFORM) }
    single<SessionStore> { throw UnsupportedOperationException(JVM_PLATFORM) }
    single<PlatformSensorProvider> { throw UnsupportedOperationException(JVM_PLATFORM) }
    single { TrackerConfig() }
    single { VehicleClass.CAR }
}
