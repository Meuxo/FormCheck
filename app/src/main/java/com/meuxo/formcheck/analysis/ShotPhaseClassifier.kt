package com.meuxo.formcheck.analysis

import com.meuxo.formcheck.domain.model.PoseLandmarks
import com.meuxo.formcheck.domain.model.ShotPhase
import com.meuxo.formcheck.util.AngleUtils
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rule-based finite state machine that labels every frame with a [ShotPhase].
 * Driven entirely by landmark geometry — no ML model.
 *
 * Note: image Y axis grows downward, so "upward motion" means a *decreasing* Y.
 *
 * Thresholds were widened from the original spec because the strict
 * AND-conditions caused legitimate shots to never reach RELEASE/FOLLOW_THROUGH,
 * which then starved the evaluators of the right frames to score.
 */
@Singleton
class ShotPhaseClassifier @Inject constructor() {

    fun classify(frames: List<PoseLandmarks>): List<ShotPhase> {
        if (frames.isEmpty()) return emptyList()
        if (frames.size == 1) return listOf(classifySingle(frames.first()))

        val phases = ArrayList<ShotPhase>(frames.size)
        var current = ShotPhase.SETUP
        var prevWristY = frames.first().rightWrist.y

        frames.forEach { lm ->
            val kneeAng = kneeAngle(lm)
            val elbowAng = elbowAngle(lm)
            val wristY = lm.rightWrist.y
            val foreheadY = lm.foreheadY
            val wristDeltaY = wristY - prevWristY  // negative => moving up

            current = when (current) {
                ShotPhase.SETUP -> {
                    // Either the knees start to bend, or the wrist visibly
                    // begins lifting — whichever comes first.
                    if (kneeAng < 165f || wristDeltaY < -2f) ShotPhase.LOAD
                    else ShotPhase.SETUP
                }
                ShotPhase.LOAD -> {
                    // Move on as soon as the wrist clearly starts climbing.
                    if (wristDeltaY < -1.5f) ShotPhase.LIFT else ShotPhase.LOAD
                }
                ShotPhase.LIFT -> {
                    // Release: arm has reached near-extension OR the wrist
                    // has cleared forehead level.
                    if (elbowAng > 140f || wristY < foreheadY) ShotPhase.RELEASE
                    else ShotPhase.LIFT
                }
                ShotPhase.RELEASE -> {
                    // Wrist Y stops climbing → ball has left the hand.
                    if (wristDeltaY > -0.5f) ShotPhase.FOLLOW_THROUGH
                    else ShotPhase.RELEASE
                }
                ShotPhase.FOLLOW_THROUGH -> ShotPhase.FOLLOW_THROUGH
            }

            phases.add(current)
            prevWristY = wristY
        }
        return phases
    }

    /** Best-effort static-image phase from a single set of landmarks. */
    fun classifySingle(lm: PoseLandmarks): ShotPhase {
        val knee = kneeAngle(lm)
        val elbow = elbowAngle(lm)
        val wristAboveForehead = lm.rightWrist.y < lm.foreheadY
        return when {
            wristAboveForehead && elbow > 160f -> ShotPhase.FOLLOW_THROUGH
            wristAboveForehead -> ShotPhase.RELEASE
            knee < 145f -> ShotPhase.LOAD
            elbow in 90f..150f -> ShotPhase.LIFT
            else -> ShotPhase.SETUP
        }
    }

    private fun kneeAngle(lm: PoseLandmarks): Float {
        val left = AngleUtils.angleBetween(lm.leftHip, lm.leftKnee, lm.leftAnkle)
        val right = AngleUtils.angleBetween(lm.rightHip, lm.rightKnee, lm.rightAnkle)
        return (left + right) / 2f
    }

    private fun elbowAngle(lm: PoseLandmarks): Float =
        AngleUtils.angleBetween(lm.rightShoulder, lm.rightElbow, lm.rightWrist)
}
