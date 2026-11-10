package com.meuxo.formcheck.tracking.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.meuxo.formcheck.tracking.BallObs
import com.meuxo.formcheck.tracking.LandmarkSmoother
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.PoseFrame
import com.meuxo.formcheck.tracking.RimObs
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.tracking.TrackedFrame
import com.meuxo.formcheck.tracking.TrackingConfig
import com.meuxo.formcheck.tracking.ball.BallCandidate
import com.meuxo.formcheck.tracking.ball.BallKalmanTracker
import com.meuxo.formcheck.tracking.ball.ClassicalBallRimDetector
import com.meuxo.formcheck.tracking.ball.LearnedBallRimDetector
import com.meuxo.formcheck.tracking.ball.RgbImage
import com.meuxo.formcheck.tracking.geom.Stats
import com.meuxo.formcheck.tracking.geom.Vec2
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decode → pose → ball → rim → smoothing for a whole clip. Runs on the caller's dispatcher
 * (the worker uses Dispatchers.Default). No frame is ever dropped from the timeline.
 */
class ClipTracker @Inject constructor(@ApplicationContext private val context: Context) {

    data class Options(
        val trackBall: Boolean = true,
        /** Run pose on every Nth frame and interpolate between (shootaround uses 2). */
        val poseEveryNth: Int = 1,
        /** Normalised (0..1) point on the shooter chosen by the user, if several people. */
        val shooterSeed: Vec2? = null,
        /** Rim edges tapped by the user (normalised 0..1); overrides automatic rim detection. */
        val rimTapsNorm: Pair<Vec2, Vec2>? = null
    )

    suspend fun track(
        file: File,
        options: Options,
        onProgress: (Float) -> Unit = {},
        onFrameBitmap: ((index: Int, Bitmap) -> Unit)? = null
    ): TrackedClip {
        val decoder = VideoFrameDecoder()
        val info = decoder.probe(file)
        val pose = PoseEngine(context, numPoses = if (options.shooterSeed != null) 4 else 1)
        val learned = if (options.trackBall) LearnedBallRimDetector.create(context) else null
        val classical = ClassicalBallRimDetector()
        val raw = ArrayList<TrackedFrame>()
        var tracker: BallKalmanTracker? = null
        var lastTarget: Vec2? = null
        var w = 0; var h = 0
        val rimSamples = ArrayList<Pair<Int, RimObs>>()
        var lastRimSampleUs = Long.MIN_VALUE
        var rimOverride: RimObs? = null

        try {
            decoder.decode(file, TrackingConfig.POSE_INPUT_LONG_SIDE) { idx, tUs, bmp ->
                w = bmp.width; h = bmp.height
                if (rimOverride == null && options.rimTapsNorm != null) {
                    val (l, r) = options.rimTapsNorm
                    rimOverride = RimObs(((l.x + r.x) / 2 * w).toFloat(), ((l.y + r.y) / 2 * h).toFloat(),
                        (kotlin.math.abs(r.x - l.x) * w).toFloat(), 1f, com.meuxo.formcheck.tracking.RimSource.USER_TAP)
                }
                if (lastTarget == null && options.shooterSeed != null) {
                    lastTarget = Vec2(options.shooterSeed.x * w, options.shooterSeed.y * h)
                }
                // ---- Pose ----
                val p: PoseFrame? = if (idx % options.poseEveryNth == 0) {
                    PoseEngine.pick(pose.detect(bmp, tUs), lastTarget)?.also { lastTarget = it.hipMidPx() }
                } else null

                // ---- Ball ----
                var ball: BallObs? = null
                if (options.trackBall) {
                    if (tracker == null) tracker = BallKalmanTracker(hypot(w.toDouble(), h.toDouble()))
                    val t = tUs / 1e6
                    val hint = tracker!!.predictedPosition(t)
                    val cands = ArrayList<BallCandidate>()
                    learned?.let { cands += it.detectBallBitmap(bmp) }
                    if ((cands.maxOfOrNull { it.score } ?: 0.0) < TrackingConfig.LEARNED_BALL_MIN_SCORE) {
                        val (small, s) = downscale(bmp, TrackingConfig.DETECTOR_INPUT_LONG_SIDE / 2)
                        classical.detectBall(small, tUs, hint?.let { it * s }).forEach {
                            cands += it.copy(x = it.x / s, y = it.y / s, radius = it.radius / s)
                        }
                    }
                    ball = tracker!!.update(t, cands.sortedByDescending { it.score })

                    // ---- Rim (sampled twice a second; the camera is assumed static) ----
                    // With a user-placed rim, samples are still taken to detect camera movement.
                    if (tUs - lastRimSampleUs >= 500_000) {
                        lastRimSampleUs = tUs
                        val (small, s) = downscale(bmp, TrackingConfig.DETECTOR_INPUT_LONG_SIDE)
                        (learned?.detectRim(small) ?: classical.detectRim(small))?.let { r ->
                            rimSamples += idx to r.copy(x = (r.x / s).toFloat(), y = (r.y / s).toFloat(), width = (r.width / s).toFloat())
                        }
                    }
                }
                raw += TrackedFrame(idx, tUs, p, ball)
                onFrameBitmap?.invoke(idx, bmp)
                if (info.durationUs > 0) onProgress((tUs.toFloat() / info.durationUs).coerceIn(0f, 1f))
            }
        } finally {
            pose.close(); learned?.close(); classical.close()
        }

        val filled = if (options.poseEveryNth > 1) interpolateSkipped(raw, options.poseEveryNth) else raw
        val smoothed = LandmarkSmoother.smoothAll(filled)
        val (rim, movedAt) = rimOverride?.let { it to movedAfterOverride(it, rimSamples) } ?: consensusRim(rimSamples)
        val timeScale = info.captureFps?.let { cap -> (info.declaredFps ?: cap) / cap } ?: 1.0
        return TrackedClip(w, h, smoothed, rim, movedAt, timeScale)
    }

    /** Median of the first samples; flags the first later run of samples that disagree. */
    private fun consensusRim(samples: List<Pair<Int, RimObs>>): Pair<RimObs?, Int?> {
        if (samples.size < 2) return samples.firstOrNull()?.second to null
        val first = samples.take(6)
        val rim = RimObs(
            Stats.median(first.map { it.second.x.toDouble() }).toFloat(),
            Stats.median(first.map { it.second.y.toDouble() }).toFloat(),
            Stats.median(first.map { it.second.width.toDouble() }).toFloat(),
            Stats.median(first.map { it.second.confidence.toDouble() }).toFloat() *
                (first.size / 6f).coerceAtMost(1f),
            first.first().second.source
        )
        return rim to movedAfterOverride(rim, samples)
    }

    private fun movedAfterOverride(rim: RimObs, samples: List<Pair<Int, RimObs>>): Int? {
        var run = 0
        for ((idx, s) in samples) {
            val d = hypot((s.x - rim.x).toDouble(), (s.y - rim.y).toDouble())
            if (d > TrackingConfig.RIM_MOVED_FRACTION * rim.width) { if (++run >= 2) return idx } else run = 0
        }
        return null
    }

    private fun downscale(bmp: Bitmap, longSide: Int): Pair<RgbImage, Double> {
        val s = minOf(1.0, longSide.toDouble() / max(bmp.width, bmp.height))
        val sw = max(1, (bmp.width * s).roundToInt()); val sh = max(1, (bmp.height * s).roundToInt())
        val scaled = if (s < 1.0) Bitmap.createScaledBitmap(bmp, sw, sh, true) else bmp
        val px = IntArray(sw * sh)
        scaled.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (scaled !== bmp) scaled.recycle()
        return RgbImage(sw, sh, px) to s
    }

    /** Linear interpolation of poses on frames that were skipped (not on frames where detection failed). */
    private fun interpolateSkipped(frames: List<TrackedFrame>, nth: Int): List<TrackedFrame> {
        val out = frames.toMutableList()
        for (i in 1 until frames.size - 1) {
            if (frames[i].pose != null || i % nth == 0) continue
            val a = frames[i - 1].pose ?: continue
            val b = frames[i + 1].pose ?: continue
            val ta = frames[i - 1].timeUs; val tb = frames[i + 1].timeUs
            val f = if (tb == ta) 0.5f else ((frames[i].timeUs - ta).toFloat() / (tb - ta))
            val img = FloatArray(a.img.size) { a.img[it] + (b.img[it] - a.img[it]) * f }
            val wld = FloatArray(a.world.size) { a.world[it] + (b.world[it] - a.world[it]) * f }
            val vis = FloatArray(Lm.COUNT) { minOf(a.vis[it], b.vis[it]) }
            out[i] = TrackedFrame(frames[i].index, frames[i].timeUs, PoseFrame(img, wld, vis), frames[i].ball)
        }
        return out
    }
}
