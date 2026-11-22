package com.exkmptracker.vision.di

import com.exkmptracker.cooperative.EdgeVisionController
import com.exkmptracker.ports.TrackerClock
import com.exkmptracker.vision.BoxUnits
import com.exkmptracker.vision.CameraCalibration
import com.exkmptracker.vision.VisionTiming
import com.exkmptracker.vision.YoloBackendOpener
import com.exkmptracker.vision.YoloConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import org.koin.test.KoinTest
import org.koin.test.get

class VisionGraphTest : KoinTest {
    private var opened = 0

    @AfterTest
    fun tearDown() {
        if (KoinPlatform.getKoinOrNull() != null) stopKoin()
    }

    @Test
    fun resolvingAndStartingWithoutASourceDoesNotOpenTheBackend() {
        val opener = YoloBackendOpener {
            opened += 1
            error("backend must not open during graph resolution")
        }
        startKoin {
            modules(
                visionCommonModule,
                module { single<TrackerClock> { FakeClock() } },
            )
        }
        val request = VisionRequest(
            source = null,
            calibration = CameraCalibration(
                imageWidth = 640,
                imageHeight = 480,
                fx = 500.0,
                fy = 500.0,
                cx = 320.0,
                cy = 240.0,
                cameraHeightMeters = 1.2,
                heightStdDevMeters = 0.05,
                contactStdDevPixels = 2.0,
                maxGroundRangeMeters = 40.0,
            ),
            config = YoloConfig.cocoVehicles(BoxUnits.NORMALIZED),
            timing = VisionTiming(),
        )
        val controller = get<EdgeVisionController> { parametersOf(request, opener) }
        assertEquals(0, opened)
        var reported: String? = null
        controller.start(onObservations = {}, onError = { reported = it })
        assertEquals("Vision unavailable: no camera frame source supplied", reported)
        assertEquals(0, opened)
        controller.stop()
    }
}

private class FakeClock : TrackerClock {
    override fun wallTimeMs(): Long = 0
    override fun monotonicSeconds(): Double = 0.0
}
