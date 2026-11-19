package com.exkmptracker.vision

import com.exkmptracker.cooperative.EdgeVisionController
import com.exkmptracker.cooperative.ObservedPeers
import com.exkmptracker.cooperative.VehicleClass
import com.exkmptracker.cooperative.VisualObservation
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.*

data class VisionFrame(
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
    /** Detached, immutable after publication. Not a borrowed CameraX/Core Video buffer. */
    val rgba: ByteArray,
    val captureMonotonicSeconds: Double,
    val pose: CameraPose?,
    val poseMonotonicSeconds: Double?,
) {
    init {
        require(width > 0 && height > 0 && rowStrideBytes >= width * 4L)
        require((height - 1L) * rowStrideBytes + width * 4L <= rgba.size)
    }
}

/** Subscribe/unsubscribe may run on a worker; the host must marshal camera API calls as needed. */
fun interface VisionFrameSource {
    fun subscribe(onFrame: (VisionFrame) -> Unit): VisionFrameSubscription
}

fun interface VisionFrameSubscription {
    fun unsubscribe()
}

data class VisionTiming(
    val maxFps: Int = 30,
    val maxFrameAgeSeconds: Double = 0.5,
    val maxPoseSkewSeconds: Double = 0.05,
    /** Opt in to a heuristic range derivative. It is not a full velocity or a vehicle identity. */
    val estimateRadialSpeed: Boolean = false,
) {
    init {
        require(maxFps in 1..30)
        require(maxFrameAgeSeconds > 0 && maxFrameAgeSeconds <= 2)
        require(maxPoseSkewSeconds >= 0 && maxPoseSkewSeconds <= 0.25)
    }
}

internal fun frameIssue(frame: VisionFrame, calibration: CameraCalibration, timing: VisionTiming, now: Double): String? {
    val capture = frame.captureMonotonicSeconds
    val poseTime = frame.poseMonotonicSeconds
    return when {
        !now.isFinite() || !capture.isFinite() || capture < 0 || capture > now -> "invalid or future capture time"
        now - capture > timing.maxFrameAgeSeconds -> "stale capture time"
        frame.width != calibration.imageWidth || frame.height != calibration.imageHeight -> "frame size differs from calibration"
        frame.pose == null || poseTime == null -> "missing calibrated camera pose"
        !poseTime.isFinite() || poseTime !in 0.0..now -> "invalid or future camera pose time"
        abs(poseTime - capture) > timing.maxPoseSkewSeconds -> "camera pose does not match capture time"
        else -> null
    }
}

private data class Projected(val vehicle: VehicleClass, val ground: GroundPoint)

private fun radialSpeed(previous: List<Projected>, current: List<Projected>, index: Int, dt: Double): Double? {
    if (dt !in 0.1..0.5) return null
    fun matches(a: Projected, b: Projected) = a.vehicle == b.vehicle &&
        a.ground.variance <= 4 && b.ground.variance <= 4 &&
        hypot(a.ground.east - b.ground.east, a.ground.north - b.ground.north) <= 1 + 30 * dt
    val target = current[index]
    val old = previous.singleOrNull { matches(it, target) } ?: return null
    if (current.count { matches(old, it) } != 1) return null
    return ((target.ground.distance - old.ground.distance) / dt).takeIf { abs(it) <= 122.3 }
}

@OptIn(ExperimentalAtomicApi::class, DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class NativeEdgeVision(
    private val source: VisionFrameSource?,
    private val calibration: CameraCalibration?,
    config: YoloConfig,
    private val timing: VisionTiming,
    private val monotonicNow: () -> Double,
    private val open: (() -> YoloBackend)?,
) : EdgeVisionController {
    private val config = config.copy(classMapping = config.classMapping.toMap())
    private class Session {
        val job = Job()
        val frames = Channel<VisionFrame>(Channel.CONFLATED)
    }
    private val active = AtomicReference<Session?>(null)
    private val exclusive = Mutex()

    override fun start(onObservations: (ObservedPeers) -> Unit, onError: (String) -> Unit) {
        val unavailable = when {
            open == null -> "no host model supplied"
            calibration == null -> "no camera calibration supplied"
            source == null -> "no camera frame source supplied"
            else -> null
        }
        if (unavailable != null) {
            onError("Vision unavailable: $unavailable")
            return
        }
        val session = Session()
        if (!active.compareAndSet(null, session)) {
            session.job.cancel()
            return
        }
        CoroutineScope(Dispatchers.Default + session.job).launch {
            try {
                exclusive.withLock {
                    // Interpreter/delegate initialization, predictions, and close all share one native thread.
                    newSingleThreadContext("TrackerVision").use { worker ->
                        withContext(worker) {
                            runSession(session, onObservations, onError)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (active.load() === session) onError("Vision unavailable: ${failure.message ?: "native inference failed"}")
            } finally {
                active.compareAndSet(session, null)
                session.frames.cancel()
                session.job.cancel()
            }
        }
    }

    override fun stop() {
        val old = active.exchange(null) ?: return
        old.job.cancel()
        old.frames.cancel()
    }

    private suspend fun runSession(
        session: Session,
        onObservations: (ObservedPeers) -> Unit,
        onError: (String) -> Unit,
    ) {
        val backend = checkNotNull(open).invoke()
        try {
            val c = checkNotNull(calibration)
            val letterbox = Letterbox(c.imageWidth, c.imageHeight, backend.width, backend.height)
            val rgb = ByteArray(backend.width * backend.height * 3)
            currentCoroutineContext().ensureActive()
            val subscription = checkNotNull(source).subscribe { frame ->
                if (active.load() === session) session.frames.trySend(frame)
            }
            try {
                var previous = emptyList<Projected>()
                var previousCapture = Double.NEGATIVE_INFINITY
                var lastCaptureSeen = Double.NEGATIVE_INFINITY
                var lastInferenceStart = Double.NEGATIVE_INFINITY
                var lastDiagnostic = Double.NEGATIVE_INFINITY
                var idle = false
                var sequence = 0L
                while (active.load() === session) {
                    val frame = withTimeoutOrNull(2_000) { session.frames.receive() }
                    if (frame == null) {
                        if (!idle && active.load() === session) onError("Vision unavailable: camera frames are not arriving")
                        idle = true
                        continue
                    }
                    idle = false
                    val now = monotonicNow()
                    fun diagnostic(reason: String) {
                        if (now - lastDiagnostic < 1 || active.load() !== session) return
                        lastDiagnostic = now
                        onError("Vision dropped evidence: $reason")
                    }
                    val issue = frameIssue(frame, c, timing, now)
                    if (issue != null) {
                        diagnostic(issue)
                        continue
                    }
                    val capture = frame.captureMonotonicSeconds
                    if (capture <= lastCaptureSeen) {
                        diagnostic("non-monotonic capture time")
                        continue
                    }
                    lastCaptureSeen = capture
                    if (now - lastInferenceStart < 1.0 / timing.maxFps) continue
                    letterbox.rgb(frame.rgba, frame.rowStrideBytes, rgb)
                    currentCoroutineContext().ensureActive()
                    lastInferenceStart = monotonicNow()
                    val detections = decode(backend.predict(rgb), letterbox, config)
                    currentCoroutineContext().ensureActive()
                    if (monotonicNow() - capture > timing.maxFrameAgeSeconds) {
                        diagnostic("inference finished after frame expired")
                        continue
                    }
                    val projected = detections.mapNotNull { d ->
                        val ground = projectContact(c, checkNotNull(frame.pose), (d.l + d.r) / 2 - 0.5, d.b - 0.5)
                        if (ground == null || ground.distance !in 0.1..200.0 || ground.variance > 10_000) null
                        else Projected(config.classMapping.getValue(d.classId), ground)
                    }.take(8)
                    if (detections.isNotEmpty() && projected.isEmpty()) diagnostic("no reliable ground-plane intersection")
                    val observations = projected.mapIndexed { index, p ->
                        VisualObservation(
                            "$sequence:$index", p.vehicle, p.ground.distance, p.ground.azimuth,
                            if (timing.estimateRadialSpeed) radialSpeed(previous, projected, index, capture - previousCapture)
                            else null,
                            p.ground.variance.coerceAtLeast(0.01),
                        )
                    }
                    sequence++
                    // Keep the older sample until the speed baseline is long enough.
                    if (capture - previousCapture >= 0.1 || projected.isEmpty()) {
                        previousCapture = capture
                        previous = projected
                    }
                    if (active.load() === session) onObservations(ObservedPeers(capture, observations))
                }
            } finally {
                subscription.unsubscribe()
            }
        } finally {
            backend.close()
        }
    }
}
