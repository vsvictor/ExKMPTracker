@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.exkmptracker.di

import kotlin.native.HiddenFromObjC
import org.koin.core.qualifier.named

/** Qualifiers for process dispatchers and one foreground-service / Swift runtime scope. */
@HiddenFromObjC
object TrackerQualifiers {
    val main = named("exkmptracker.dispatcher.main")
    val io = named("exkmptracker.dispatcher.io")
    val compute = named("exkmptracker.dispatcher.compute")
    val runtimeScope = named("exkmptracker.runtime.scope")
    val runtime = named("exkmptracker.runtime")
}
