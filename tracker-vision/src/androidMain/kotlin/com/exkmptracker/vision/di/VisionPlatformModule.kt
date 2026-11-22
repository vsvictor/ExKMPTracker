package com.exkmptracker.vision.di

import com.exkmptracker.vision.YoloBackendOpener
import com.exkmptracker.vision.acceleratedAndroidVisionOptions
import com.exkmptracker.vision.openBackendOrCpu
import java.io.File
import org.koin.dsl.module

actual val visionPlatformModule = module {
    factory<YoloBackendOpener> { parameters ->
        val model = parameters.get<File>()
        YoloBackendOpener {
            openBackendOrCpu(model, ::acceleratedAndroidVisionOptions)
        }
    }
}
