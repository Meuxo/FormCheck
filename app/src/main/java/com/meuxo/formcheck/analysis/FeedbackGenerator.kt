package com.meuxo.formcheck.analysis

import com.meuxo.formcheck.domain.model.PhaseFeedback
import com.meuxo.formcheck.domain.model.ShotPhase
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Builds the per-phase, human-readable feedback bullet lists. */
@Singleton
class FeedbackGenerator @Inject constructor() {

    fun build(
        angles: AngleEvaluator.Result,
        balance: BalanceScorer.Result,
        guideHand: GuideHandDetector.Result
    ): List<PhaseFeedback> {
        val setup = PhaseFeedback(
            phase = ShotPhase.SETUP,
            score = balance.score,
            notes = listOf(
                "Hip-over-ankle offset: ${balance.offsetPctShoulderWidth.roundToInt()}% of shoulder width.",
                if (balance.score >= 80)
                    "Solid base — center of mass over your feet."
                else
                    "Square up before you load — try to keep hips stacked over the ankles."
            )
        )

        val load = PhaseFeedback(
            phase = ShotPhase.LOAD,
            score = angles.kneeBendScore,
            notes = listOf(
                "Knee bend (deepest): ${angles.kneeBendDeg.roundToInt()}° (target 100–160°).",
                if (angles.kneeBendScore >= 80)
                    "Good load — strong base for the lift."
                else if (angles.kneeBendDeg > 160f)
                    "Try to dip a little deeper to load more power."
                else
                    "You're dipping a lot — a slightly shallower load can help your rhythm."
            )
        )

        val lift = PhaseFeedback(
            phase = ShotPhase.LIFT,
            score = angles.setPointScore,
            notes = listOf(
                if (angles.setPointScore >= 80)
                    "Set point at or above forehead — clean release window."
                else
                    "Set point sits ~${angles.setPointBelowPctBodyHeight.roundToInt()}% of body height below the forehead — raise it for a quicker release."
            )
        )

        val releaseNotes = mutableListOf<String>()
        releaseNotes += "Elbow extension at apex: ${angles.elbowAngleDeg.roundToInt()}° (target 150–180°)."
        releaseNotes += if (angles.elbowFlarePct <= 20f) {
            "Elbow stacked under wrist — clean alignment."
        } else {
            "Elbow drifts ${angles.elbowFlarePct.roundToInt()}% of shoulder width off the wrist line — try to keep it directly under the ball."
        }
        val releaseScore = (angles.elbowAngleScore + angles.elbowFlareScore) / 2
        val release = PhaseFeedback(
            phase = ShotPhase.RELEASE,
            score = releaseScore,
            notes = releaseNotes
        )

        val followThrough = PhaseFeedback(
            phase = ShotPhase.FOLLOW_THROUGH,
            score = guideHand.score,
            notes = listOf(guideHand.verdict)
        )

        return listOf(setup, load, lift, release, followThrough)
    }
}
