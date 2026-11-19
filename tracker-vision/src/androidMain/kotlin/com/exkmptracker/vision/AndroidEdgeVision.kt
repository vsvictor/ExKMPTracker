package com.exkmptracker.vision

import android.os.SystemClock
import com.exkmptracker.cooperative.EdgeVisionController
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** When adding a delegate, release it here after the Interpreter closes, on the same thread. */
data class AndroidVisionOptions(
    val options: Interpreter.Options,
    val release: () -> Unit = {},
)

fun androidEdgeVision(
    modelFile: File?,
    source: VisionFrameSource?,
    calibration: CameraCalibration?,
    config: YoloConfig,
    timing: VisionTiming = VisionTiming(),
    optionsFactory: () -> AndroidVisionOptions = {
        AndroidVisionOptions(Interpreter.Options().setNumThreads(4))
    },
): EdgeVisionController = NativeEdgeVision(
    source, calibration, config, timing,
    { SystemClock.elapsedRealtimeNanos() / 1e9 },
    modelFile?.let { file ->
        {
            require(file.isFile && file.canRead() && file.length() > 0) { "host TFLite model is missing or unreadable" }
            val settings = optionsFactory()
            val interpreter = try {
                Interpreter(file, settings.options)
            } catch (failure: Throwable) {
                settings.release()
                throw failure
            }
            try {
                AndroidYoloBackend(interpreter, settings.release)
            } catch (failure: Throwable) {
                try { interpreter.close() } finally { settings.release() }
                throw failure
            }
        }
    },
)

private class AndroidYoloBackend(private val interpreter: Interpreter, private val release: () -> Unit) : YoloBackend {
    override val width: Int
    override val height: Int
    private val input: ByteBuffer
    private val output: ByteBuffer
    private val result: YoloOutput
    private val rgbScale = FloatArray(256) { it / 255f }

    init {
        require(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1) {
            "Expected one TFLite input and one raw YOLO output"
        }
        val source = interpreter.getInputTensor(0)
        val shape = source.shape()
        require(source.dataType() == DataType.FLOAT32 && shape.size == 4 && shape[0] == 1 && shape[3] == 3) {
            "Expected FLOAT32 TFLite input [1,H,W,3]"
        }
        height = shape[1]
        width = shape[2]
        require(width in 1..2048 && height in 1..2048) { "Unsupported TFLite image dimensions" }
        val destination = interpreter.getOutputTensor(0)
        require(destination.dataType() == DataType.FLOAT32) { "Expected FLOAT32 TFLite output" }
        val layout = OutputLayout(destination.shape())
        input = buffer(width * height * 3 * Float.SIZE_BYTES)
        output = buffer(layout.count * 84 * Float.SIZE_BYTES)
        result = YoloOutput(layout, FloatArray(layout.count * 84))
    }

    override fun predict(rgb: ByteArray): YoloOutput {
        input.clear()
        val floats = input.asFloatBuffer()
        for (byte in rgb) floats.put(rgbScale[byte.toInt() and 255])
        output.clear()
        interpreter.run(input, output)
        output.rewind()
        output.asFloatBuffer().get(result.values)
        return result
    }

    override fun close() {
        try { interpreter.close() } finally { release() }
    }

    private fun buffer(bytes: Int) = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
}
