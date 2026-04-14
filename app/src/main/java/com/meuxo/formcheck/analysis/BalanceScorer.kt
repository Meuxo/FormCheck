package com.meuxo.formcheck.analysis

import android.graphics.PointF
import com.meuxo.formcheck.domain.model.PoseLandmarks
import com.meuxo.formcheck.domain.model.ShotPhase
import com.meuxo.formcheck.util.AngleUtils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scores how well the shooter stays over their base of support.
 *
 * The original 5% threshold was too aggressive — pose landmark noise alone
 * (especially on the hips and ankles, which are often partially occluded by
 * shorts/the ball) routinely produces 8–15% offsets even on a perfectly
 * balanced shot. Loosened to give full credit anywhere under ~18% and to
 * decay gently from there.
 */
@Singleton
class BalanceScorer @Inject constructor() {

    data class Result(val score: Int, val offsetPctShoulderWidth: Float)

    fun evaluate(frames: List<PoseLandmarks>, phases: List<ShotPhase>): Result {
        if (frames.isEmpty()) return Result(0, 0f)

        // Sample at the median LIFT/RELEASE frame if available, otherwise
        // the middle of the video.
        val candidates = if (phases.size == frames.size) {
            phases.indices.filter {
                phases[it] == ShotPhase.LIFT ||
                    phases[it] == ShotPhase.RELEASE
            }
        } else emptyList()

        val idx = if (candidates.isNotEmpty()) candidates[candidates.size / 2]
        else frames.size / 2

        val lm = frames[idx]

        val com = midpoint(lm.leftHip, lm.rightHip)
        val bos = midpoint(lm.leftAnkle, lm.rightAnkle)
        val offset = AngleUtils.horizontalOffset(com, bos)
        val shoulderWidth = AngleUtils
            .horizontalOffset(lm.leftShoulder, lm.rightShoulder)
            .coerceAtLeast(1f)
        val pct = (offset / shoulderWidth) * 100f

        val score = when {
            pct <= 18f -> 100
            pct <= 30f -> (100f - (pct - 18f) * (15f / 12f)).toInt()
            pct <= 50f -> (85f - (pct - 30f) * (30f / 20f)).toInt()
            else -> (55f - (pct - 50f) * 0.5f).toInt().coerceAtLeast(30)
        }.coerceIn(0, 100)

        return Result(score, pct)
    }

    private fun midpoint(a: PointF, b: PointF): PointF =
        PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)
}
