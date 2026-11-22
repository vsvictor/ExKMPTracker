@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.exkmptracker.di

import com.exkmptracker.ports.TrackingRpcTransport
import kotlin.native.HiddenFromObjC
import kotlin.reflect.KClass
import org.koin.core.Koin
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.startKoin
import org.koin.core.error.DefinitionOverrideException
import org.koin.core.module.Module
import org.koin.mp.KoinPlatform

@OptIn(KoinInternalApi::class)
@HiddenFromObjC
internal fun Koin.declares(type: KClass<*>): Boolean =
    instanceRegistry.instances.values.any { factory ->
        val bean = factory.beanDefinition
        bean.primaryType == type || type in bean.secondaryTypes
    }

/**
 * Loads [modules] into the process Koin. A second call keeps the already registered
 * [TrackingRpcTransport] instead of replacing it.
 */
@OptIn(KoinInternalApi::class)
@HiddenFromObjC
internal fun loadTrackerModules(
    modules: List<Module>,
    alreadyReady: (Koin) -> Boolean = { koin -> koin.declares(TrackingRpcTransport::class) },
) {
    val current = KoinPlatform.getKoinOrNull()
    if (current == null) {
        startKoin { modules(modules) }
        return
    }
    try {
        current.loadModules(modules, allowOverride = false)
    } catch (error: DefinitionOverrideException) {
        if (!alreadyReady(current)) throw error
    }
}

/**
 * Loads [core] when [alreadyReady] is false, then always loads [extra] with override.
 *
 * A host that already called `startKoin` still receives the logger, config, vehicle, map matcher
 * and vision modules. A later call does not reload [core], so the first transport and queue stay.
 */
@OptIn(KoinInternalApi::class)
@HiddenFromObjC
internal fun ensureTrackerModules(
    core: List<Module>,
    extra: List<Module> = emptyList(),
    alreadyReady: (Koin) -> Boolean = { koin -> koin.declares(TrackingRpcTransport::class) },
) {
    val readyBefore = KoinPlatform.getKoinOrNull()?.let(alreadyReady) == true
    if (!readyBefore) loadTrackerModules(core, alreadyReady)
    if (extra.isNotEmpty()) KoinPlatform.getKoin().loadModules(extra, allowOverride = true)
}
