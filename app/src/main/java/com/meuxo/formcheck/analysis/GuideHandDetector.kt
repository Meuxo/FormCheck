package com.meuxo.formcheck.analysis

import com.meuxo.formcheck.domain.model.PoseLandmarks
import com.meuxo.formcheck.domain.model.ShotPhase
import com.meuxo.formcheck.util.AngleUtils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Determines how passive (or active) the off-hand is during the
 * RELEASE / FOLLOW_THROUGH phases.
 *
 * Approach: compare the *forward* (X-axis) drift of the left wrist relative
 * to the right wrist during the release window. The shooting hand will
 * always travel further than the guide hand on a clean release; if the
 * guide hand pushes through, the ratio of left/right drift jumps.
 *
 * The original detector was very brittle — single-frame noise on the
 * left-wrist landmark would push perfectly fine releases into the
 * "guide hand pushed forward" bucket. This version compares averaged
 * positions across the release window and uses much more forgiving
 * thresholds.
 */
@Singleton
class GuideHandDetector @Inject constructor() {

    data class Result(val score: Int, val verdict: String)

    fun evaluate(frames: List<PoseLandmarks>, phases: List<ShotPhase>): Result {
        if (frames.size < 3) {
            return Result(85, "Guide hand looks neutral")
        }

        val releaseIdxs: List<Int> =
            if (phases.size == frames.size) {
                phases.indices.filter {
                    phases[it] == ShotPhase.RELEASE ||
                        phases[it] == ShotPhase.FOLLOW_THROUGH
                }
            } else emptyList()

        // Pick a "before release" and "after release" sample. If phase
        // detection failed, use the first and last quarter of frames.
        val (beforeFrames, afterFrames) = if (releaseIdxs.size >= 2) {
            val mid = releaseIdxs.size / 2
            frames.slice(releaseIdxs.take(mid)) to
                frames.slice(releaseIdxs.drop(mid))
        } else {
            val q = (frames.size / 4).coerceAtLeast(1)
            frames.take(q) to frames.takeLast(q)
        }

        if (beforeFrames.isEmpty() || afterFrames.isEmpty()) {
            return Result(85, "Guide hand looks neutral")
        }

        val leftWristMove = movementBetween(beforeFrames, afterFrames) { it.leftWrist }
        val rightWristMove = movementBetween(beforeFrames, afterFrames) { it.rightWrist }
            .coerceAtLeast(1f)
        val ratio = leftWristMove / rightWristMove

        return when {
            ratio <= 0.35f ->
                Result(100, "Guide hand stayed passive — clean release")
            ratio <= 0.55f ->
                Result(85, "Guide hand mostly passive")
            ratio <= 0.80f ->
                Result(70, "Slight guide-hand involvement at release")
            else ->
                Result(50, "Guide hand pushed through — try to keep it still")
        }
    }

    private inline fun movementBetween(
        before: List<PoseLandmarks>,
        after: List<PoseLandmarks>,
        selector: (PoseLandmarks) -> android.graphics.PointF
    ): Float {
        val a = averagePoint(before.map(selector))
        val b = averagePoint(after.map(selector))
        return AngleUtils.distance(a, b)
    }

    private fun averagePoint(points: List<android.graphics.PointF>): android.graphics.PointF {
        if (points.isEmpty()) return android.graphics.PointF(0f, 0f)
        val x = points.sumOf { it.x.toDouble() } / points.size
        val y = points.sumOf { it.y.toDouble() } / points.size
        return android.graphics.PointF(x.toFloat(), y.toFloat())
    }
}
