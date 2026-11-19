package com.exkmptracker.vision

import com.exkmptracker.cooperative.VehicleClass
import kotlin.math.*

enum class BoxUnits { INPUT_PIXELS, NORMALIZED }

data class YoloConfig(
    val classMapping: Map<Int, VehicleClass>,
    val boxUnits: BoxUnits,
    val scoreThreshold: Float = 0.35f,
    val nmsIouThreshold: Float = 0.45f,
    val maxDetections: Int = 32,
) {
    init {
        require(classMapping.isNotEmpty() && classMapping.keys.all { it in 0..79 })
        require(scoreThreshold > 0 && scoreThreshold <= 1)
        require(nmsIouThreshold > 0 && nmsIouThreshold < 1)
        require(maxDetections in 1..128)
    }

    companion object {
        /** Standard zero-based COCO label IDs; there is no COCO trolleybus class. */
        fun cocoVehicles(units: BoxUnits) = YoloConfig(
            mapOf(2 to VehicleClass.CAR, 5 to VehicleClass.BUS, 7 to VehicleClass.TRUCK), units,
        )
    }
}

internal interface YoloBackend {
    val width: Int
    val height: Int
    fun predict(rgb: ByteArray): YoloOutput
    fun close()
}

internal class OutputLayout(shape: IntArray) {
    private val dimensions = when {
        shape.size == 2 -> shape
        shape.size == 3 && shape[0] == 1 -> shape.copyOfRange(1, 3)
        else -> throw IllegalArgumentException("Expected [N,84], [84,N] or a leading singleton batch")
    }
    val channelsFirst: Boolean
    val count: Int

    init {
        require((dimensions[0] == 84) xor (dimensions[1] == 84)) {
            "Expected one unambiguous axis of size 84 (xywh + 80 class probabilities)"
        }
        channelsFirst = dimensions[0] == 84
        count = dimensions[if (channelsFirst) 1 else 0]
        require(count in 1..100_000) { "Unsupported YOLO candidate count" }
    }

    fun index(row: Int, channel: Int) = if (channelsFirst) channel * count + row else row * 84 + channel
}

internal class YoloOutput(val layout: OutputLayout, val values: FloatArray) {
    init { require(values.size == layout.count * 84) }
    operator fun get(row: Int, channel: Int) = values[layout.index(row, channel)]
}

internal class Letterbox(val sw: Int, val sh: Int, val width: Int, val height: Int) {
    private val scale = min(width.toDouble() / sw, height.toDouble() / sh)
    private val rw = (sw * scale).roundToInt().coerceIn(1, width)
    private val rh = (sh * scale).roundToInt().coerceIn(1, height)
    private val left = (width - rw) / 2
    private val top = (height - rh) / 2
    // The resized extent uses edge coordinates; camera intrinsics use pixel-center coordinates.
    fun sourceX(x: Double) = (x - left) * sw / rw
    fun sourceY(y: Double) = (y - top) * sh / rh

    private val xs = (0 until rw).map { ((it + 0.5) * sw / rw - 0.5).coerceIn(0.0, sw - 1.0) }
    private val ys = (0 until rh).map { ((it + 0.5) * sh / rh - 0.5).coerceIn(0.0, sh - 1.0) }

    fun rgb(rgba: ByteArray, rowStride: Int, output: ByteArray) {
        output.fill(114)
        for (dy in 0 until rh) {
            val sy = ys[dy]
            val y0 = sy.toInt()
            val dy1 = (sy - y0).toFloat()
            val row0 = y0 * rowStride
            val row1 = min(y0 + 1, sh - 1) * rowStride
            for (dx in 0 until rw) {
                val sx = xs[dx]
                val x0 = sx.toInt()
                val dx1 = (sx - x0).toFloat()
                val col0 = x0 * 4
                val col1 = min(x0 + 1, sw - 1) * 4
                val offset = ((dy + top) * width + dx + left) * 3
                for (ch in 0..2) {
                    val a = rgba[row0 + col0 + ch].toInt() and 255
                    val b = rgba[row0 + col1 + ch].toInt() and 255
                    val c = rgba[row1 + col0 + ch].toInt() and 255
                    val d = rgba[row1 + col1 + ch].toInt() and 255
                    val upper = a + (b - a) * dx1
                    val lower = c + (d - c) * dx1
                    output[offset + ch] = (upper + (lower - upper) * dy1).roundToInt().toByte()
                }
            }
        }
    }
}

internal data class Detection(
    val classId: Int, val score: Float, val l: Double, val t: Double, val r: Double, val b: Double,
) {
    fun iou(other: Detection): Double {
        val i = max(0.0, min(r, other.r) - max(l, other.l)) * max(0.0, min(b, other.b) - max(t, other.t))
        return i / ((r - l) * (b - t) + (other.r - other.l) * (other.b - other.t) - i)
    }
}

internal fun decode(output: YoloOutput, letterbox: Letterbox, config: YoloConfig): List<Detection> {
    val candidates = ArrayList<Detection>()
    val xScale = if (config.boxUnits == BoxUnits.NORMALIZED) letterbox.width.toDouble() else 1.0
    val yScale = if (config.boxUnits == BoxUnits.NORMALIZED) letterbox.height.toDouble() else 1.0
    for (row in 0 until output.layout.count) {
        var best = -1
        var score = -1f
        for (c in 0..79) {
            val s = output[row, c + 4]
            require(s in 0f..1f) { "Unsupported YOLO class score: expected finite probabilities (not logits)" }
            if (s > score) {
                score = s
                best = c
            }
        }
        if (score < config.scoreThreshold || best !in config.classMapping) continue
        val x = output[row, 0] * xScale
        val y = output[row, 1] * yScale
        val w = output[row, 2] * xScale
        val h = output[row, 3] * yScale
        if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) continue
        val l = letterbox.sourceX(x - w / 2)
        val r = letterbox.sourceX(x + w / 2)
        val t = letterbox.sourceY(y - h / 2).coerceAtLeast(0.0)
        val b = letterbox.sourceY(y + h / 2)
        // Bottom-center is not a reliable ground contact when a box touches the sides or bottom.
        if (l <= 0.5 || r >= letterbox.sw - 0.5 || b >= letterbox.sh - 0.5 || b <= t || r <= l) continue
        candidates += Detection(best, score, l, t, r, b)
    }
    val kept = ArrayList<Detection>()
    for (candidate in candidates.sortedByDescending { it.score }) {
        if (kept.none { it.classId == candidate.classId && it.iou(candidate) > config.nmsIouThreshold }) {
            kept += candidate
            if (kept.size >= config.maxDetections) break
        }
    }
    return kept
}
