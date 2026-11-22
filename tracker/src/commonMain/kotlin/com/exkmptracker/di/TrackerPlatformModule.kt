@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.exkmptracker.di

import kotlin.native.HiddenFromObjC
import org.koin.core.module.Module

@HiddenFromObjC
expect val trackerPlatformModule: Module
