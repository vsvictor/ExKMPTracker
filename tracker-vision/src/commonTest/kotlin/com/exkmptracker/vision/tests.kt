package com.exkmptracker.vision

import com.exkmptracker.cooperative.VehicleClass
import kotlin.test.*

class VisionTests {
    private val calibration = CameraCalibration(640, 480, 400.0, 400.0, 319.5, 239.5, 2.0, 0.1, 1.0, 80.0)
    private val pose = CameraPose(90.0, 0.0, 0.0, 0.5, 0.2)

    @Test fun projectsKnownGroundPointWithTrueNorthHeadingAndUncertainty() {
        val ground = assertNotNull(projectContact(calibration, pose, 319.5, 319.5))
        assertEquals(10.0, ground.distance, 1e-8)
        assertEquals(90.0, ground.azimuth, 1e-8)
        val right = assertNotNull(projectContact(calibration, pose, 399.5, 319.5))
        assertTrue(right.azimuth > 90)
        val noisier = assertNotNull(projectContact(calibration, pose.copy(tiltStdDevDegrees = 1.0), 319.5, 319.5))
        assertTrue(noisier.variance > ground.variance)
    }

    @Test fun appliesPitchAndRejectsHorizonAndOutsideCalibration() {
        assertNull(projectContact(calibration, pose, 320.0, 239.5))
        assertNull(projectContact(calibration, pose, 320.0, 258.0))
        assertNull(projectContact(calibration, pose, 1000.0, 400.0))
        val below = assertNotNull(projectContact(calibration, pose.copy(pitchDownDegrees = 45.0), 319.5, 239.5))
        assertEquals(2.0, below.distance, 1e-8)
        assertFailsWith<IllegalArgumentException> { calibration.copy(fx = 0.0) }
    }

    @Test fun rgbLetterboxRespectsColorOrderPaddingAndStride() {
        val rgba = byteArrayOf(127, -1, 0, 5, 22, 33, -56, 1, 99, 99, 99, 99)
        val output = ByteArray(36)
        Letterbox(2, 1, 4, 3).rgb(rgba, 12, output)
        assertEquals(listOf(127, 255, 0), output.take(3).map { it.toInt() and 255 })
        assertEquals(listOf(22, 33, 200), output.slice(9..11).map { it.toInt() and 255 })
        assertTrue(output.takeLast(12).all { it == 114.toByte() })

        val padded = ByteArray(12) { -1 }
        Letterbox(1, 2, 2, 2).rgb(byteArrayOf(10, 20, 30, 0, 9, 9, 9, 9, 40, 50, 60, 0), 8, padded)
        assertContentEquals(byteArrayOf(10, 20, 30, 114, 114, 114, 40, 50, 60, 114, 114, 114), padded)
    }

    @Test fun decodesBothLayoutsAndUsesClassAwareNmsWithoutInventingTrolleybus() {
        val letterbox = Letterbox(640, 320, 320, 320)
        val config = YoloConfig.cocoVehicles(BoxUnits.NORMALIZED)
        assertFalse(VehicleClass.TROLLEYBUS in config.classMapping.values)
        for (shape in listOf(intArrayOf(1, 5, 84), intArrayOf(84, 5))) {
            val layout = OutputLayout(shape)
            val tensor = FloatArray(5 * 84)
            fun add(row: Int, cls: Int, score: Float) {
                listOf(.5f, .5f, .25f, .25f).forEachIndexed { column, v -> tensor[layout.index(row, column)] = v }
                tensor[layout.index(row, cls + 4)] = score
            }
            add(0, 2, .8f)
            add(1, 2, .7f) // same box and class: suppression
            add(2, 5, .9f) // overlapping distinct class: retained
            add(3, 7, .05f)
            add(4, 2, .8f)
            add(4, 0, .9f) // person wins; do not relabel as a car
            val output = decode(YoloOutput(layout, tensor), letterbox, config)
            assertEquals(listOf(5, 2), output.map { it.classId })
            assertEquals(240.0, output.first().l)
            assertEquals(240.0, output.first().b)
        }
    }

    @Test fun rejectsUnknownAndAmbiguousTensorShapes() {
        for (shape in listOf(intArrayOf(84, 84), intArrayOf(1, 8400, 85), intArrayOf(2, 84, 32))) {
            assertFailsWith<IllegalArgumentException> { OutputLayout(shape) }
        }
    }

    @Test fun rejectsFuturePoseAndMismatchedCameraCalibration() {
        val c = calibration.copy(imageWidth = 2, imageHeight = 1, cx = 0.5, cy = 0.0)
        val frame = VisionFrame(2, 1, 8, ByteArray(8), 10.0, pose, 10.0)
        val timing = VisionTiming()
        assertNull(frameIssue(frame, c, timing, 10.02))
        assertContains(checkNotNull(frameIssue(frame.copy(poseMonotonicSeconds = 10.03), c, timing, 10.02)), "future")
        assertContains(checkNotNull(frameIssue(frame.copy(pose = null), c, timing, 10.02)), "missing")
        assertContains(checkNotNull(frameIssue(frame, calibration, timing, 10.02)), "size")
        assertContains(checkNotNull(frameIssue(frame, c, timing, 11.0)), "stale")
    }

    @Test fun missingModelDoesNotSubscribeOrPublish() {
        val controller = NativeEdgeVision(
            VisionFrameSource { fail("capture must not start") }, calibration,
            YoloConfig.cocoVehicles(BoxUnits.INPUT_PIXELS), VisionTiming(), { 0.0 }, null,
        )
        val diagnostics = mutableListOf<String>()
        controller.start({ fail("should not emit evidence") }, diagnostics::add)
        controller.stop()
        assertEquals(listOf("Vision unavailable: no host model supplied"), diagnostics)
    }
}
