package com.meuxo.formcheck.analysis

import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.PoseFrame
import com.meuxo.formcheck.tracking.Side
import com.meuxo.formcheck.tracking.geom.Angles

/**
 * Joint angles from WORLD landmarks (3D, meters), gated on visibility. Every function
 * returns null when a required landmark is below the visibility threshold, so callers
 * cannot silently use a hidden joint.
 */
object PoseMath {

    fun knee(p: PoseFrame, side: Side): Double? =
        angle(p, side.hip, side.knee, side.ankle)

    fun elbow(p: PoseFrame, side: Side): Double? =
        angle(p, side.shoulder, side.elbow, side.wrist)

    fun ankle(p: PoseFrame, side: Side): Double? =
        angle(p, side.knee, side.ankle, side.toe)

    /** Mean of both knees when both are visible, otherwise the visible one. */
    fun kneeEither(p: PoseFrame): Pair<Double, Int>? {
        val l = knee(p, Side.LEFT)
        val r = knee(p, Side.RIGHT)
        return when {
            l != null && r != null -> (l + r) / 2 to 2
            l != null -> l to 1
            r != null -> r to 1
            else -> null
        }
    }

    fun angle(p: PoseFrame, a: Int, v: Int, b: Int): Double? {
        if (!p.allVisible(a, v, b)) return null
        return Angles.jointAngle(p.w(a), p.w(v), p.w(b)).takeIf { !it.isNaN() }
    }

    /** Trunk angle from image vertical, hips→shoulders. Null if hips/shoulders hidden. */
    fun trunkLeanImage(p: PoseFrame): Double? {
        if (!p.allVisible(Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, Lm.LEFT_HIP, Lm.RIGHT_HIP)) return null
        return Angles.angleFromVerticalUp(p.hipMidPx(), p.shoulderMidPx()).takeIf { !it.isNaN() }
    }

    /** Minimum visibility of the given landmarks, as a confidence contribution. */
    fun visConf(p: PoseFrame, vararg idx: Int): Double = p.minVis(*idx).toDouble().coerceIn(0.0, 1.0)
}
