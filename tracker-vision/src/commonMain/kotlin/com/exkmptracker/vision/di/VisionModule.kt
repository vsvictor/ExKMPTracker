@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package com.exkmptracker.vision.di

import com.exkmptracker.cooperative.EdgeVisionController
import com.exkmptracker.ports.TrackerClock
import com.exkmptracker.vision.CameraCalibration
import com.exkmptracker.vision.NativeEdgeVision
import com.exkmptracker.vision.VisionFrameSource
import com.exkmptracker.vision.VisionTiming
import com.exkmptracker.vision.YoloBackendOpener
import com.exkmptracker.vision.YoloConfig
import kotlin.native.HiddenFromObjC
import org.koin.dsl.module

data class VisionRequest(
    val source: VisionFrameSource?,
    val calibration: CameraCalibration?,
    val config: YoloConfig,
    val timing: VisionTiming,
)

@HiddenFromObjC
val visionCommonModule = module {
    factory<EdgeVisionController> { parameters ->
        val request = parameters.get<VisionRequest>()
        val opener = parameters.getOrNull<YoloBackendOpener>()
        NativeEdgeVision(
            source = request.source,
            calibration = request.calibration,
            config = request.config,
            timing = request.timing,
            monotonicNow = get<TrackerClock>()::monotonicSeconds,
            open = opener?.let { backend -> { backend.open() } },
        )
    }
}
