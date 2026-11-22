@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.exkmptracker.vision.di

import kotlin.native.HiddenFromObjC
import org.koin.core.module.Module

@HiddenFromObjC
expect val visionPlatformModule: Module
