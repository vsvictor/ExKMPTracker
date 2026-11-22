package com.exkmptracker.vision.di

import com.exkmptracker.vision.YoloBackendOpener
import org.koin.dsl.module

actual val visionPlatformModule = module {
    factory<YoloBackendOpener> { error("JVM target has no vision inference") }
}
