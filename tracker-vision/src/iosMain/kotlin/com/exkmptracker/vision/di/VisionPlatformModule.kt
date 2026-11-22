@file:OptIn(
    kotlin.experimental.ExperimentalObjCRefinement::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package com.exkmptracker.vision.di

import com.exkmptracker.vision.CoreMlYoloBackend
import com.exkmptracker.vision.YoloBackendOpener
import com.exkmptracker.vision.appleResult
import kotlin.native.HiddenFromObjC
import org.koin.dsl.module
import platform.CoreML.MLComputeUnitsAll
import platform.CoreML.MLModel
import platform.CoreML.MLModelConfiguration
import platform.Foundation.NSURL

@HiddenFromObjC
actual val visionPlatformModule = module {
    factory<YoloBackendOpener> { parameters ->
        val url = parameters.get<NSURL>()
        YoloBackendOpener {
            val configuration = MLModelConfiguration()
            configuration.computeUnits = MLComputeUnitsAll
            val model = appleResult("Core ML model load") { error ->
                MLModel.modelWithContentsOfURL(url, configuration, error)
            }
            CoreMlYoloBackend(model)
        }
    }
}
