package com.meuxo.formcheck.analysis

import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.PoseFrame
import com.meuxo.formcheck.tracking.ProjectileFit
import com.meuxo.formcheck.tracking.ProjectileFitter
import com.meuxo.formcheck.tracking.Side
import com.meuxo.formcheck.tracking.TimedPoint
import com.meuxo.formcheck.tracking.TrackedFrame
import com.meuxo.formcheck.tracking.TrackingConfig
import com.meuxo.formcheck.tracking.geom.Stats
import com.meuxo.formcheck.tracking.geom.Vec2

/** Frame indices of one shot. Null fields could not be determined from the footage. */
data class ShotEvents(
    val startFrame: Int,
    val dipFrame: Int?,
    /** True if [dipFrame] came from the ball; false if from the shooting wrist. */
    val dipFromBall: Boolean,
    val setFrame: Int?,
    val releaseFrame: Int,
    val arc: ProjectileFit?,
    /** Indices of the ball positions used for the arc fit. */
    val arcFrames: List<Int>,
    val ballRadiusPx: Double,
    val endFrame: Int
)

object ShotEventDetector {

    /** Distance from ball surface to the shooting hand (the closer of wrist and index finger). */
    fun handGap(p: PoseFrame, side: Side, ball: Vec2, radius: Double): Double? {
        val pts = listOf(side.wrist, side.index).filter { p.visible(it) }
        if (pts.isEmpty()) return null
        return pts.minOf { p.px(it).distanceTo(ball) } - radius
    }

    /**
     * Finds every shot release in the clip (spec 3e): the first frame the ball separates from
     * the shooting hand by more than ~half a ball diameter and keeps moving away for at least
     * [TrackingConfig.RELEASE_CONFIRM_MS], while moving upward from above the shoulders.
     */
    fun detect(frames: List<TrackedFrame>, side: Side): List<ShotEvents> {
        val n = frames.size
        val radii = frames.mapNotNull { it.ball?.takeIf { b -> !b.predicted }?.radius?.toDouble() }
        if (radii.size < 3) return emptyList()
        val typicalR = Stats.median(radii)
        val sepThreshold = TrackingConfig.RELEASE_SEPARATION_DIAMETERS * 2 * typicalR

        fun gap(i: Int): Double? {
            val f = frames[i]
            val b = f.ball ?: return null
            val p = f.pose ?: return null
            return handGap(p, side, b.center, typicalR)
        }

        val shots = ArrayList<ShotEvents>()
        var lastShotEnd = -1
        var i = 1
        while (i < n) {
            val g0 = gap(i - 1)
            val g1 = gap(i)
            if (g0 == null || g1 == null || i <= lastShotEnd || g0 > sepThreshold || g1 <= sepThreshold) { i++; continue }
            // Must have been held for at least ~150 ms before.
            val heldSince = heldStart(frames, i - 1, ::gap, sepThreshold)
            if (heldSince == null || frames[i - 1].timeSec - frames[heldSince].timeSec < 0.15) { i++; continue }
            // Keeps moving away, upward, for RELEASE_CONFIRM_MS.
            if (!confirmSeparation(frames, i, ::gap)) { i++; continue }
            val p = frames[i].pose!!
            val b = frames[i].ball!!
            val shoulderY = if (p.visible(side.shoulder)) p.px(side.shoulder).y else null
            if (shoulderY == null || b.y > shoulderY) { i++; continue }

            val release = i
            val start = heldSince
            // Dip: lowest ball point (max y) while held. Fallback: lowest shooting wrist.
            val heldRange = start until release
            val ballDip = heldRange.filter { frames[it].ball?.predicted == false }.maxByOrNull { frames[it].ball!!.y }
            val dip = ballDip ?: heldRange.filter { frames[it].pose?.visible(side.wrist) == true }
                .maxByOrNull { frames[it].pose!!.px(side.wrist).y }
            val set = dip?.let { d ->
                (d..release).mapNotNull { k -> frames[k].pose?.let { pp -> PoseMath.elbow(pp, side)?.let { k to it } } }
                    .minByOrNull { it.second }?.first
            }
            val (arc, arcFrames) = fitArc(frames, release, typicalR)
            val end = arcFrames.lastOrNull() ?: (release + 1).coerceAtMost(n - 1)
            shots += ShotEvents(start, dip, ballDip != null, set, release, arc, arcFrames, typicalR, end)
            lastShotEnd = end
            i = end + 1
        }
        return shots
    }

    private fun heldStart(frames: List<TrackedFrame>, from: Int, gap: (Int) -> Double?, thr: Double): Int? {
        var k = from
        var start: Int? = null
        val tLimit = frames[from].timeSec - 2.0
        while (k >= 0 && frames[k].timeSec >= tLimit) {
            val g = gap(k) ?: break
            if (g > thr) break
            start = k
            k--
        }
        return start
    }

    private fun confirmSeparation(frames: List<TrackedFrame>, i: Int, gap: (Int) -> Double?): Boolean {
        val t0 = frames[i].timeSec
        var prevGap = gap(i) ?: return false
        var k = i + 1
        var sawRise = false
        while (k < frames.size && (frames[k].timeSec - t0) * 1000 < TrackingConfig.RELEASE_CONFIRM_MS) {
            val g = gap(k) ?: return false
            if (g < prevGap) return false
            val b0 = frames[k - 1].ball!!; val b1 = frames[k].ball!!
            if (b1.y < b0.y) sawRise = true
            prevGap = g
            k++
        }
        return k > i + 1 && sawRise
    }

    /**
     * Post-release ball positions until the ball stops behaving like a free projectile
     * (track lost, or more than 2 s), fitted with RANSAC.
     */
    fun fitArc(frames: List<TrackedFrame>, release: Int, radius: Double): Pair<ProjectileFit?, List<Int>> {
        val pts = ArrayList<TimedPoint>()
        val idx = ArrayList<Int>()
        val t0 = frames[release].timeSec
        var misses = 0
        var k = release
        while (k < frames.size && frames[k].timeSec - t0 < 2.0) {
            val b = frames[k].ball
            if (b == null) { if (++misses > TrackingConfig.KALMAN_MAX_COAST_FRAMES) break } else {
                misses = 0
                if (!b.predicted) { pts += TimedPoint(frames[k].timeSec, b.x.toDouble(), b.y.toDouble()); idx += k }
            }
            k++
        }
        if (pts.size < TrackingConfig.ARC_MIN_POINTS) return null to idx
        val fit = ProjectileFitter.fit(pts, TrackingConfig.ARC_INLIER_RADII * radius) ?: return null to idx
        if (fit.ay <= 0) return null to idx   // not falling: not a free flight
        return fit to idx
    }

    /** Arc-fit confidence from the RMS residual relative to ball radius and the inlier share. */
    fun arcConfidence(fit: ProjectileFit, radius: Double): Double {
        val resid = (fit.rmsResidualPx / radius) / TrackingConfig.ARC_MAX_RESIDUAL_RADII
        val inlierShare = fit.inlierCount.toDouble() / fit.totalCount
        return ((1.0 - resid).coerceIn(0.0, 1.0) * inlierShare).coerceIn(0.0, 1.0)
    }
}

data class HandednessEstimate(val side: Side, val confidence: Double, val fromBall: Boolean)

/**
 * Detects the shooting hand (spec 3c): the wrist under the ball at the set point. Without a
 * ball, falls back to the wrist that rises highest (the shooting arm extends overhead).
 */
object HandednessDetector {
    fun detect(frames: List<TrackedFrame>): HandednessEstimate? {
        var left = 0.0; var right = 0.0
        for (f in frames) {
            val p = f.pose ?: continue
            val b = f.ball?.takeIf { !it.predicted } ?: continue
            if (!p.allVisible(Lm.LEFT_WRIST, Lm.RIGHT_WRIST, Lm.NOSE)) continue
            if (b.y > p.px(Lm.NOSE).y + b.radius * 2) continue          // only near the set point
            val lw = p.px(Lm.LEFT_WRIST); val rw = p.px(Lm.RIGHT_WRIST)
            // "Under the ball": in contact below the ball centre, within the ball's width.
            // The guide hand sits beside the ball, so it fails the horizontal test.
            fun underScore(w: Vec2) =
                if (w.y > b.y && w.y - b.y < b.radius * 2.5 && kotlin.math.abs(w.x - b.x) < b.radius * 0.9) 1.0 else 0.0
            left += underScore(lw); right += underScore(rw)
        }
        if (left + right >= 3.0) {
            val side = if (right >= left) Side.RIGHT else Side.LEFT
            return HandednessEstimate(side, maxOf(left, right) / (left + right), fromBall = true)
        }
        // Fallback: highest wrist relative to its own shoulder.
        var lMax = Double.NEGATIVE_INFINITY; var rMax = Double.NEGATIVE_INFINITY
        for (f in frames) {
            val p = f.pose ?: continue
            if (p.allVisible(Lm.LEFT_WRIST, Lm.LEFT_SHOULDER)) lMax = maxOf(lMax, p.px(Lm.LEFT_SHOULDER).y - p.px(Lm.LEFT_WRIST).y)
            if (p.allVisible(Lm.RIGHT_WRIST, Lm.RIGHT_SHOULDER)) rMax = maxOf(rMax, p.px(Lm.RIGHT_SHOULDER).y - p.px(Lm.RIGHT_WRIST).y)
        }
        if (lMax.isInfinite() && rMax.isInfinite()) return null
        val side = if (rMax >= lMax) Side.RIGHT else Side.LEFT
        return HandednessEstimate(side, 0.5, fromBall = false)
    }
}
