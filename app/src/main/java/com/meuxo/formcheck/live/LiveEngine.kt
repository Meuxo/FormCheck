package com.meuxo.formcheck.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.meuxo.formcheck.tracking.LandmarkSmoother
import com.meuxo.formcheck.tracking.RimObs
import com.meuxo.formcheck.tracking.TrackedFrame
import com.meuxo.formcheck.tracking.TrackingConfig
import com.meuxo.formcheck.tracking.ball.BallCandidate
import com.meuxo.formcheck.tracking.ball.BallKalmanTracker
import com.meuxo.formcheck.tracking.ball.ClassicalBallRimDetector
import com.meuxo.formcheck.tracking.ball.LearnedBallRimDetector
import com.meuxo.formcheck.tracking.ball.RgbImage
import com.meuxo.formcheck.tracking.geom.Stats
import com.meuxo.formcheck.tracking.pipeline.PoseEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

/** What a live mode shows and says. */
data class LiveStatus(val lines: List<String> = emptyList(), val cue: String? = null)

/** Mode-specific live logic (shootaround counting, dribble cues). Runs on the analysis thread. */
interface LiveLogic {
    fun onFrame(frame: TrackedFrame, width: Int, height: Int, rim: RimObs?): LiveStatus
}

data class LiveState(
    val frame: TrackedFrame? = null,
    val width: Int = 0,
    val height: Int = 0,
    val rim: RimObs? = null,
    val processedFps: Double = 0.0,
    /** True once the device could not keep up and live processing stopped. */
    val recordOnly: Boolean = false,
    val status: LiveStatus = LiveStatus()
)

/**
 * Live analysis (spec 6/7): ball detector on every analysed frame at reduced resolution, pose on
 * every second frame. If the processed rate stays below [TrackingConfig.LIVE_MIN_FPS] after a
 * 3 s warm-up, live processing stops and the session continues as record-now-analyze-after.
 * Live numbers are a preview; the saved recording is always analysed in full afterwards.
 */
class LiveEngine(private val context: Context, private val logic: LiveLogic?, private val trackBall: Boolean = true) : ImageAnalysis.Analyzer {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var pose: PoseEngine? = null
    private var learned: LearnedBallRimDetector? = null
    private val classical = ClassicalBallRimDetector()
    private var tracker: BallKalmanTracker? = null
    private val smoother = LandmarkSmoother()
    private var index = 0
    private var firstNs = -1L
    private val processedAt = ArrayDeque<Long>()
    private val rimSamples = ArrayList<RimObs>()
    @Volatile private var stopped = false

    override fun analyze(image: ImageProxy) {
        try {
            if (stopped) return
            val nowNs = image.imageInfo.timestamp
            if (firstNs < 0) firstNs = nowNs
            val tUs = (nowNs - firstNs) / 1000
            val bmp = upright(image)
            if (pose == null) {
                pose = PoseEngine(context)
                learned = if (trackBall) LearnedBallRimDetector.create(context) else null
                tracker = BallKalmanTracker(hypot(bmp.width.toDouble(), bmp.height.toDouble()))
            }
            val p = if (index % 2 == 0) pose!!.detect(bmp, tUs).firstOrNull() else _state.value.frame?.pose
            val smoothed = if (index % 2 == 0) smoother.smooth(p, tUs / 1e6) else p
            var ball = null as com.meuxo.formcheck.tracking.BallObs?
            if (trackBall) {
                val t = tUs / 1e6
                val cands = ArrayList<BallCandidate>()
                learned?.let { cands += it.detectBallBitmap(bmp) }
                if ((cands.maxOfOrNull { it.score } ?: 0.0) < TrackingConfig.LEARNED_BALL_MIN_SCORE) {
                    val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    val img = RgbImage(bmp.width, bmp.height, px)
                    cands += classical.detectBall(img, tUs, tracker!!.predictedPosition(t))
                    if (index % 15 == 0) classical.detectRim(img)?.let { rimSamples += it; if (rimSamples.size > 12) rimSamples.removeAt(0) }
                }
                ball = tracker!!.update(t, cands.sortedByDescending { it.score })
            }
            val rim = consensus()
            val frame = TrackedFrame(index, tUs, smoothed, ball)
            index++
            processedAt.addLast(nowNs)
            while (processedAt.isNotEmpty() && nowNs - processedAt.first() > 2_000_000_000L) processedAt.removeFirst()
            val fps = if (processedAt.size < 2) 0.0 else (processedAt.size - 1) * 1e9 / (processedAt.last() - processedAt.first())
            val status = logic?.onFrame(frame, bmp.width, bmp.height, rim) ?: LiveStatus()
            val tooSlow = tUs > 3_000_000 && fps in 0.1..TrackingConfig.LIVE_MIN_FPS
            _state.value = LiveState(frame, bmp.width, bmp.height, rim, fps, tooSlow, status)
            bmp.recycle()
            if (tooSlow) stop()
        } catch (_: Throwable) {
            stop(); _state.value = _state.value.copy(recordOnly = true)
        } finally {
            image.close()
        }
    }

    private fun consensus(): RimObs? {
        if (rimSamples.size < 3) return null
        return RimObs(Stats.median(rimSamples.map { it.x.toDouble() }).toFloat(), Stats.median(rimSamples.map { it.y.toDouble() }).toFloat(),
            Stats.median(rimSamples.map { it.width.toDouble() }).toFloat(), 0.5f, rimSamples.last().source)
    }

    private fun upright(image: ImageProxy): Bitmap {
        val raw = image.toBitmap()
        val rot = image.imageInfo.rotationDegrees
        if (rot == 0) return raw
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { raw.recycle() }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { pose?.close() }; runCatching { learned?.close() }
        pose = null; learned = null
    }
}
