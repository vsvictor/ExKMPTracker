@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.exkmptracker.vision

import com.exkmptracker.cooperative.EdgeVisionController
import com.exkmptracker.ios.IosTrackerClock
import kotlinx.cinterop.*
import platform.CoreML.*
import platform.CoreVideo.*
import platform.Foundation.*

fun iosEdgeVision(
    compiledModelUrl: NSURL?,
    source: VisionFrameSource?,
    calibration: CameraCalibration?,
    config: YoloConfig,
    timing: VisionTiming = VisionTiming(),
    configurationFactory: () -> MLModelConfiguration = {
        MLModelConfiguration().apply { computeUnits = MLComputeUnitsAll }
    },
): EdgeVisionController = NativeEdgeVision(
    source, calibration, config, timing,
    IosTrackerClock()::monotonicSeconds,
    compiledModelUrl?.let { url ->
        {
            require(url.isFileURL()) { "A local compiled Core ML .mlmodelc URL is required" }
            val model = appleResult("Core ML model load") { error ->
                MLModel.modelWithContentsOfURL(url, configurationFactory(), error)
            }
            CoreMlYoloBackend(model)
        }
    },
)

internal inline fun <T : Any> appleResult(
    operation: String,
    call: (CPointer<ObjCObjectVar<NSError?>>) -> T?,
): T = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    call(error.ptr) ?: kotlin.error("$operation failed (${error.value?.domain ?: "unknown"}:${error.value?.code ?: 0})")
}

internal class CoreMlYoloBackend(model: MLModel) : YoloBackend {
    private var model: MLModel? = model
    private val inputName: String
    private val outputName: String
    override val width: Int
    override val height: Int
    private var tensor: MLMultiArray? = null
    private val inputChannelsFirst: Boolean
    private val inputHasBatch: Boolean
    private var pixelBuffer: CVPixelBufferRef? = null
    private val outputShape: IntArray
    private val result: YoloOutput
    private val rgbScale = FloatArray(256) { it / 255f }

    init {
        val description = model.modelDescription
        val inputs = description.inputDescriptionsByName
        val outputs = description.outputDescriptionsByName
        require(inputs.size == 1 && outputs.size == 1) { "Expected one Core ML input and one raw YOLO output" }
        inputName = inputs.keys.single() as String
        outputName = outputs.keys.single() as String
        val input = inputs.getValue(inputName) as MLFeatureDescription
        val output = outputs.getValue(outputName) as MLFeatureDescription
        require(output.type == MLFeatureTypeMultiArray) { "Expected a Core ML MLMultiArray YOLO output" }
        val outputConstraint = checkNotNull(output.multiArrayConstraint)
        require(outputConstraint.dataType == MLMultiArrayDataTypeFloat32 ||
            outputConstraint.dataType == MLMultiArrayDataTypeDouble) { "Expected Float32 or Double Core ML output" }
        outputShape = dimensions(outputConstraint.shape)
        val layout = OutputLayout(outputShape)
        result = YoloOutput(layout, FloatArray(layout.count * layout.channels))

        when (input.type) {
            MLFeatureTypeImage -> {
                val image = checkNotNull(input.imageConstraint)
                require(image.pixelFormatType == kCVPixelFormatType_32BGRA) { "Expected RGB Core ML image input (32BGRA buffer)" }
                width = image.pixelsWide.toInt()
                height = image.pixelsHigh.toInt()
                require(width in 1..2048 && height in 1..2048) { "Unsupported Core ML image dimensions" }
                inputChannelsFirst = false
                inputHasBatch = false
                pixelBuffer = memScoped {
                    val slot = alloc<CVPixelBufferRefVar>()
                    check(CVPixelBufferCreate(null, width.toULong(), height.toULong(), kCVPixelFormatType_32BGRA,
                        null, slot.ptr) == kCVReturnSuccess) { "Core Video pixel buffer allocation failed" }
                    checkNotNull(slot.value)
                }
            }
            MLFeatureTypeMultiArray -> {
                val constraint = checkNotNull(input.multiArrayConstraint)
                val shape = dimensions(constraint.shape)
                require(constraint.dataType == MLMultiArrayDataTypeFloat32 &&
                    (shape.size == 3 || (shape.size == 4 && shape[0] == 1))) {
                    "Expected Float32 Core ML RGB tensor CHW or HWC, with optional singleton batch"
                }
                inputHasBatch = shape.size == 4
                val start = if (inputHasBatch) 1 else 0
                require((shape[start] == 3) xor (shape.last() == 3)) { "Ambiguous or missing Core ML RGB axis" }
                inputChannelsFirst = shape[start] == 3
                width = if (inputChannelsFirst) shape.last() else shape[start + 1]
                height = if (inputChannelsFirst) shape[start + 1] else shape[start]
                require(width in 1..2048 && height in 1..2048) { "Unsupported Core ML tensor dimensions" }
                tensor = appleResult("Core ML tensor allocation") { error ->
                    MLMultiArray.create(shape = constraint.shape, dataType = MLMultiArrayDataTypeFloat32, error = error)
                }
            }
            else -> error("Unsupported Core ML input feature type")
        }
    }

    override fun predict(rgb: ByteArray): YoloOutput = autoreleasepool {
        val feature = tensor?.let { array ->
            val strides = dimensions(array.strides)
            val start = if (inputHasBatch) 1 else 0
            val ch = strides[if (inputChannelsFirst) start else start + 2]
            val sy = strides[if (inputChannelsFirst) start + 1 else start]
            val sx = strides[if (inputChannelsFirst) start + 2 else start + 1]
            val data = checkNotNull(array.dataPointer).reinterpret<FloatVar>()
            var i = 0
            for (y in 0 until height) for (x in 0 until width) for (c in 0..2) {
                data[y * sy + x * sx + c * ch] = rgbScale[rgb[i++].toInt() and 255]
            }
            MLFeatureValue.featureValueWithMultiArray(array)
        } ?: checkNotNull(pixelBuffer).let { pixel ->
            check(CVPixelBufferLockBaseAddress(pixel, 0uL) == kCVReturnSuccess) { "Core Video buffer lock failed" }
            try {
                val base = checkNotNull(CVPixelBufferGetBaseAddress(pixel)).reinterpret<ByteVar>()
                val stride = CVPixelBufferGetBytesPerRow(pixel).toInt()
                var src = 0
                for (y in 0 until height) for (x in 0 until width) {
                    val dst = y * stride + x * 4
                    base[dst] = rgb[src + 2]
                    base[dst + 1] = rgb[src + 1]
                    base[dst + 2] = rgb[src]
                    base[dst + 3] = (-1).toByte()
                    src += 3
                }
            } finally {
                CVPixelBufferUnlockBaseAddress(pixel, 0uL)
            }
            MLFeatureValue.featureValueWithPixelBuffer(pixel)
        }
        val provider = appleResult("Core ML input provider") { error ->
            MLDictionaryFeatureProvider(dictionary = mapOf(inputName to feature), error = error)
        }
        val prediction = appleResult("Core ML prediction") { error ->
            checkNotNull(model).predictionFromFeatures(provider, error)
        }
        val array = checkNotNull(prediction.featureValueForName(outputName)?.multiArrayValue) {
            "Core ML did not return the configured output"
        }
        require(dimensions(array.shape).contentEquals(outputShape)) { "Core ML returned a dynamic output shape" }
        val strides = dimensions(array.strides)
        val dim0 = outputShape[outputShape.size - 2]
        val dim1 = outputShape.last()
        val s0 = strides[strides.size - 2]
        val s1 = strides.last()
        when (array.dataType) {
            MLMultiArrayDataTypeFloat32 -> {
                val data = checkNotNull(array.dataPointer).reinterpret<FloatVar>()
                for (i in 0 until dim0) for (j in 0 until dim1) result.values[i * dim1 + j] = data[i * s0 + j * s1]
            }
            MLMultiArrayDataTypeDouble -> {
                val data = checkNotNull(array.dataPointer).reinterpret<DoubleVar>()
                for (i in 0 until dim0) for (j in 0 until dim1) result.values[i * dim1 + j] = data[i * s0 + j * s1].toFloat()
            }
            else -> error("Core ML returned an unsupported output data type")
        }
        result
    }

    override fun close() {
        tensor = null
        model = null
        pixelBuffer?.let { CVPixelBufferRelease(it) }
        pixelBuffer = null
    }

    private fun dimensions(values: List<*>) = IntArray(values.size) { (values[it] as NSNumber).intValue }
}
