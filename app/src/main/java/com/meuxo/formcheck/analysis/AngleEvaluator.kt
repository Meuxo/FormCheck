package com.meuxo.formcheck.analysis

import com.meuxo.formcheck.domain.model.PoseLandmarks
import com.meuxo.formcheck.domain.model.ShotPhase
import com.meuxo.formcheck.util.AngleUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Computes the four geometry-derived metrics described in section 5a, but
 * with several accuracy improvements over the literal spec:
 *
 *  1. **Best-frame selection** — for the "release" measurement we pick the
 *     frame in the video with the maximum right-arm elbow extension (not
 *     just the first frame labelled RELEASE). For "load" we pick the
 *     deepest knee bend in the LOAD window (not the first frame).
 *  2. **Robust elbow flare** — the spec's "outward angle from a vertical
 *     shot line" doesn't work in 2D. We instead measure how far the elbow
 *     drifts horizontally from directly below the wrist, normalized to
 *     shoulder width — that's what coaches actually call elbow flare.
 *  3. **Realistic scoring curves** — thresholds widened to match what
 *     ML Kit can consistently detect on real video.
 *  4. **Phase fallbacks** — if the phase classifier misses a window, we
 *     fall back to a globally-best frame so a missed phase no longer
 *     forces a 0.
 */
@Singleton
class AngleEvaluator @Inject constructor() {

    data class Result(
        val elbowFlarePct: Float,        // horizontal elbow-to-wrist offset, % of shoulder width
        val elbowFlareScore: Int,
        val elbowAngleDeg: Float,        // measured at the most-extended frame
        val elbowAngleScore: Int,
        val kneeBendDeg: Float,          // measured at the deepest-bend frame
        val kneeBendScore: Int,
        val setPointScore: Int,
        val setPointBelowPctBodyHeight: Float
    )

    fun evaluate(
        frames: List<PoseLandmarks>,
        phases: List<ShotPhase>
    ): Result {
        if (frames.isEmpty()) {
            return Result(0f, 0, 0f, 0, 0f, 0, 0, 0f)
        }

        val releaseFrame = pickReleaseFrame(frames, phases)
        val loadFrame = pickLoadFrame(frames, phases)

        // ----- Elbow flare (horizontal alignment of elbow under wrist) -----
        val elbowFlarePct = releaseFrame?.let { computeElbowFlarePct(it) } ?: 0f
        val elbowFlareScore = scoreFlare(elbowFlarePct)

        // ----- Elbow angle at release -----
        val elbowAngleDeg = releaseFrame?.let {
            AngleUtils.angleBetween(it.rightShoulder, it.rightElbow, it.rightWrist)
        } ?: 0f
        val elbowAngleScore = scoreElbowAngle(elbowAngleDeg)

        // ----- Knee bend at load -----
        val kneeBendDeg = loadFrame?.let { kneeAngle(it) } ?: 0f
        val kneeBendScore = scoreKneeBend(kneeBendDeg)

        // ----- Set point height -----
        val (setPointScore, setPointBelowPct) = releaseFrame?.let {
            scoreSetPoint(it)
        } ?: (0 to 0f)

        return Result(
            elbowFlarePct = elbowFlarePct,
            elbowFlareScore = elbowFlareScore,
            elbowAngleDeg = elbowAngleDeg,
            elbowAngleScore = elbowAngleScore,
            kneeBendDeg = kneeBendDeg,
            kneeBendScore = kneeBendScore,
            setPointScore = setPointScore,
            setPointBelowPctBodyHeight = setPointBelowPct
        )
    }

    // -----------------------------------------------------------------
    // Frame picking
    // -----------------------------------------------------------------

    /**
     * Best "release" frame: the frame in the RELEASE/LIFT window with the
     * maximum right-arm elbow angle (most extended). If those phases never
     * fired, fall back to the frame with the highest wrist position
     * (smallest wrist.y) — that's almost always the apex of the shot.
     */
    private fun pickReleaseFrame(
        frames: List<PoseLandmarks>,
        phases: List<ShotPhase>
    ): PoseLandmarks? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames.first()

        val windowed: List<PoseLandmarks> = if (phases.size == frames.size &&
            phases.any { it == ShotPhase.RELEASE || it == ShotPhase.LIFT }
        ) {
            frames.filterIndexed { i, _ ->
                phases[i] == ShotPhase.RELEASE ||
                    phases[i] == ShotPhase.LIFT ||
                    phases[i] == ShotPhase.FOLLOW_THROUGH
            }
        } else {
            // No usable phase signal — use the top quartile of frames by
            // wrist height (smallest y values).
            val sorted = frames.sortedBy { it.rightWrist.y }
            sorted.take((frames.size / 4).coerceAtLeast(1))
        }

        return windowed.maxByOrNull {
            AngleUtils.angleBetween(it.rightShoulder, it.rightElbow, it.rightWrist)
        } ?: frames.minByOrNull { it.rightWrist.y }
    }

    /**
     * Best "load" frame: the frame in the LOAD window with the smallest
     * knee angle (deepest bend). Fallback: the deepest-bend frame in the
     * whole video.
     */
    private fun pickLoadFrame(
        frames: List<PoseLandmarks>,
        phases: List<ShotPhase>
    ): PoseLandmarks? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames.first()

        val windowed: List<PoseLandmarks> = if (phases.size == frames.size &&
            phases.any { it == ShotPhase.LOAD }
        ) {
            frames.filterIndexed { i, _ -> phases[i] == ShotPhase.LOAD }
        } else {
            frames
        }

        return windowed.minByOrNull { kneeAngle(it) }
            ?: frames.minByOrNull { kneeAngle(it) }
    }

    // -----------------------------------------------------------------
    // Metric computation
    // -----------------------------------------------------------------

    /**
     * Horizontal distance from elbow to wrist, expressed as a percentage of
     * shoulder width. A perfectly stacked elbow-under-wrist release sits
     * near 0; a flared release climbs toward 50–80%.
     */
    private fun computeElbowFlarePct(lm: PoseLandmarks): Float {
        val flarePx = abs(lm.rightElbow.x - lm.rightWrist.x)
        val shoulderWidth = abs(lm.leftShoulder.x - lm.rightShoulder.x)
            .coerceAtLeast(1f)
        return (flarePx / shoulderWidth) * 100f
    }

    /**
     * Score curve for elbow flare.
     *  - <= 20% of shoulder width:  100  (elbow tucked under wrist)
     *  - 20–35%:                    linear 100 → 80
     *  - 35–55%:                    linear 80  → 55
     *  - >= 55%:                    floors out at ~30
     */
    private fun scoreFlare(pct: Float): Int = when {
        pct <= 20f -> 100
        pct <= 35f -> (100f - (pct - 20f) * (20f / 15f)).toInt()
        pct <= 55f -> (80f - (pct - 35f) * (25f / 20f)).toInt()
        else -> (55f - (pct - 55f) * 0.5f).toInt().coerceAtLeast(25)
    }.coerceIn(0, 100)

    /**
     * Elbow angle at full extension. Real shooters land 155–178° at apex.
     * We give full credit anywhere in 150–180° and a gentle 2 pts/° penalty
     * outside that band. (Was 3 pts/° from a tighter 155–175°.)
     */
    private fun scoreElbowAngle(deg: Float): Int {
        if (deg in 150f..180f) return 100
        val outside = if (deg < 150f) 150f - deg else deg - 180f
        return (100f - 2f * outside).toInt().coerceIn(0, 100)
    }

    /**
     * Knee bend at the deepest dip. The original 130–150° band was way too
     * narrow — many proven shooters dip more or less than that. We give
     * full credit anywhere in 100–160° and a 2 pts/° penalty outside.
     */
    private fun scoreKneeBend(deg: Float): Int {
        if (deg in 100f..160f) return 100
        val outside = if (deg < 100f) 100f - deg else deg - 160f
        return (100f - 2f * outside).toInt().coerceIn(0, 100)
    }

    private fun kneeAngle(lm: PoseLandmarks): Float {
        val left = AngleUtils.angleBetween(lm.leftHip, lm.leftKnee, lm.leftAnkle)
        val right = AngleUtils.angleBetween(lm.rightHip, lm.rightKnee, lm.rightAnkle)
        return (left + right) / 2f
    }

    /**
     * Set-point height. Wrist at or above the forehead is full credit.
     * If it's below we measure how far below as a percentage of body height
     * and lose 8 pts per 5% (was 10 pts per 5%).
     */
    private fun scoreSetPoint(lm: PoseLandmarks): Pair<Int, Float> {
        val wristY = lm.rightWrist.y
        val foreheadY = lm.foreheadY
        if (wristY <= foreheadY) return 100 to 0f

        val ankleAvgY = (lm.leftAnkle.y + lm.rightAnkle.y) / 2f
        val bodyHeight = abs(ankleAvgY - foreheadY).coerceAtLeast(1f)
        val belowPct = ((wristY - foreheadY) / bodyHeight) * 100f
        val score = (100f - 8f * (belowPct / 5f)).toInt().coerceIn(0, 100)
        return score to belowPct
    }
}
