@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.exkmptracker.di

import com.exkmptracker.cooperative.VehicleClass
import com.exkmptracker.ios.IosPointIdGenerator
import com.exkmptracker.ios.IosProtectedStorage
import com.exkmptracker.ios.IosSessionStore
import com.exkmptracker.ios.IosTrackerClock
import com.exkmptracker.ios.IosTrackerLogger
import com.exkmptracker.ios.IosTrackQueue
import com.exkmptracker.model.TrackerConfig
import com.exkmptracker.ports.PointIdGenerator
import com.exkmptracker.ports.SessionStore
import com.exkmptracker.ports.TrackQueue
import com.exkmptracker.ports.TrackQueueStorage
import com.exkmptracker.ports.TrackerClock
import com.exkmptracker.ports.TrackerLogger
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.binds
import org.koin.dsl.module

/** Process singles. The runtime scope is opened by [initKoinIos], not by this module. */
@HiddenFromObjC
actual val trackerPlatformModule: Module = module {
    single<CoroutineDispatcher>(TrackerQualifiers.main) { Dispatchers.Main }
    single<CoroutineDispatcher>(TrackerQualifiers.io) { Dispatchers.Default }
    single<CoroutineDispatcher>(TrackerQualifiers.compute) { Dispatchers.Default }
    singleOf(::IosTrackerClock) { bind<TrackerClock>() }
    singleOf(::IosPointIdGenerator) { bind<PointIdGenerator>() }
    singleOf(::IosTrackerLogger) { bind<TrackerLogger>() }
    single { TrackerConfig() }
    single { VehicleClass.CAR }
    single { IosProtectedStorage() }
    single<IosTrackQueue> { IosTrackQueue(get()) } binds arrayOf(TrackQueue::class, TrackQueueStorage::class)
    single { IosSessionStore(get<IosProtectedStorage>()) } binds arrayOf(SessionStore::class)
}
