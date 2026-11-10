package com.meuxo.formcheck.tracking

import com.meuxo.formcheck.tracking.geom.Stats
import kotlinx.serialization.Serializable

@Serializable
enum class ScaleSource(val label: String) {
    RIM("rim (18 in opening)"),
    BALL("ball diameter"),
    USER_HEIGHT("your entered height")
}

/** Pixels per meter in one depth plane of the scene. */
data class Scale(val pxPerMeter: Double, val source: ScaleSource, val confidence: Double)

/**
 * Real-distance calibration (spec 3c).
 *
 * A pinhole camera has a different scale at every depth, so one number cannot serve the
 * whole scene. This class therefore gives two scales:
 *  - [shooterPlane] for things at the shooter's depth (release height, drift, jump height).
 *    Priority: ball diameter while the ball is in the hands, then user height. The rim is
 *    not used here because it sits at a different depth than the shooter.
 *  - [rimPlane] for things at the rim's depth. Uses the rim's 18 in (0.4572 m) inner
 *    diameter as seen on screen.
 *
 * The rim's 10 ft height cannot be used directly from one camera without knowing where the
 * floor below the rim is in the image, which is usually out of frame; the rim width gives
 * the same information more robustly. This deviation from the spec's literal priority list
 * is documented in docs/CHANGELOG.md (phase 5).
 */
object ScaleCalibration {
    const val RIM_INNER_DIAMETER_M = 0.4572

    /** Ball diameters (meters) per size. Size 7 value from the spec (9.43 in). */
    fun ballDiameterM(size: Int): Double = when (size) {
        5 -> 27.5 / Math.PI * 0.0254   // 27.5 in circumference
        6 -> 28.5 / Math.PI * 0.0254   // 28.5 in circumference
        else -> 9.43 * 0.0254
    }

    /**
     * Ball scale: median detected ball radius over frames where the ball is in the hands
     * (within one diameter of either wrist). Detector boxes are not pixel-exact, so the
     * confidence is capped.
     */
    fun fromBall(frames: List<TrackedFrame>, range: IntRange, ballDiameterM: Double): Scale? {
        val radii = range.mapNotNull { i ->
            val f = frames.getOrNull(i) ?: return@mapNotNull null
            val b = f.ball ?: return@mapNotNull null
            if (b.predicted) return@mapNotNull null
            val p = f.pose ?: return@mapNotNull null
            val near = listOf(Lm.LEFT_WRIST, Lm.RIGHT_WRIST).any { w ->
                p.visible(w) && p.px(w).distanceTo(b.center) < b.radius * 3.0
            }
            if (near) b.radius.toDouble() else null
        }
        if (radii.size < 3) return null
        val r = Stats.median(radii)
        val spread = Stats.stdDev(radii) / r
        val conf = (0.8 * (1.0 - spread.coerceIn(0.0, 1.0))).coerceIn(0.2, 0.8)
        return Scale(2 * r / ballDiameterM, ScaleSource.BALL, conf)
    }

    /**
     * Height scale: stature in pixels estimated from the eyes-to-heel distance on frames
     * where the person stands upright (largest values), divided by the eye-height fraction.
     */
    fun fromUserHeight(frames: List<TrackedFrame>, range: IntRange, heightM: Double): Scale? {
        if (heightM <= 0) return null
        val spans = range.mapNotNull { i ->
            val p = frames.getOrNull(i)?.pose ?: return@mapNotNull null
            if (!p.allVisible(Lm.LEFT_EYE, Lm.RIGHT_EYE)) return@mapNotNull null
            val heels = listOf(Lm.LEFT_HEEL, Lm.RIGHT_HEEL).filter { p.visible(it) }
            if (heels.isEmpty()) return@mapNotNull null
            val heelY = heels.maxOf { p.px(it).y }
            val eyeY = (p.px(Lm.LEFT_EYE).y + p.px(Lm.RIGHT_EYE).y) / 2
            heelY - eyeY
        }
        if (spans.size < 3) return null
        // Upright frames are the tallest; use the 90th percentile to ignore crouches.
        val eyeToHeel = Stats.percentile(spans, 0.9)
        val staturePx = eyeToHeel / TrackingConfig.EYE_HEIGHT_FRACTION_OF_STATURE
        return Scale(staturePx / heightM, ScaleSource.USER_HEIGHT, 0.6)
    }

    fun fromRim(rim: RimObs?): Scale? {
        if (rim == null || rim.width <= 0f) return null
        val conf = if (rim.source == RimSource.USER_TAP) 0.7 else 0.5 * rim.confidence
        return Scale(rim.width / RIM_INNER_DIAMETER_M, ScaleSource.RIM, conf)
    }

    fun shooterPlane(
        frames: List<TrackedFrame>,
        range: IntRange,
        ballDiameterM: Double,
        userHeightM: Double?
    ): Scale? = fromBall(frames, range, ballDiameterM)
        ?: userHeightM?.let { fromUserHeight(frames, range, it) }

    fun rimPlane(rim: RimObs?): Scale? = fromRim(rim)
}
