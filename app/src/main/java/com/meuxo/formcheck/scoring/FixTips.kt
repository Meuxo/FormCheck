package com.meuxo.formcheck.scoring

import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.MetricId

/** One specific tip for the "biggest fix" card, chosen by which side of the range the value fell. */
object FixTips {
    fun tip(m: Metric): String? {
        val v = m.value ?: return null
        val r = ScoringConfig.targets[m.id]
        val low = r != null && v < r.low
        return when (m.id) {
            MetricId.RELEASE_ANGLE -> if (low) "Your shot is flat. Finish with your wrist higher and push the ball up, not out." else "Your arc is very high. Push slightly more toward the rim instead of straight up."
            MetricId.ENTRY_ANGLE -> if (low) "The ball enters flat. More arc gives it a bigger window through the rim." else "The ball drops in very steeply. A little less arc keeps the shot easier to control."
            MetricId.ELBOW_RELEASE -> "Finish taller: fully extend the shooting arm toward the rim before the ball leaves."
            MetricId.ELBOW_SET -> if (low) "Your elbow is very bent at the set point. Let the forearm open toward an L shape." else "Your elbow opens early. Load the ball into an L at the set point before extending."
            MetricId.KNEE_BEND -> if (low) "You dip very deep. A shallower knee bend can make the shot quicker." else "Bend your knees more in the dip so your legs power the shot."
            MetricId.ELBOW_FLARE -> "Keep your elbow under the ball: point it at the rim, not out to the side."
            MetricId.GUIDE_HAND -> "Keep the guide hand still at release. Let it come off the ball sideways, not push forward."
            MetricId.TRUNK_LEAN -> "Stay upright at release. Jump straight up instead of leaning."
            MetricId.FOLLOW_THROUGH -> "Hold your follow-through until the ball reaches the rim."
            MetricId.BALANCE_DRIFT -> "Keep your hips over your feet from dip to release."
            MetricId.LANDING_DRIFT -> "Land where you took off. Jump straight up rather than forward or sideways."
            MetricId.CONSISTENCY_RELEASE_ANGLE -> "Your release angle varies shot to shot. Groove the same finish every rep with close-range form shooting."
            MetricId.CONSISTENCY_RELEASE_HEIGHT -> "Your release point moves around. Aim to let go at the same height every time."
            MetricId.CONSISTENCY_TIMING -> "Your rhythm changes between shots. Use the same dip-to-release tempo each rep."
            MetricId.STANCE_LOWNESS -> "Get lower: sit your hips down and bend your knees more while you dribble."
            MetricId.BALL_PROTECTION -> "Keep the ball low and outside your knee, with your off arm up."
            MetricId.FLUIDITY -> "Steady your rhythm: try to keep the same time between bounces."
            MetricId.FUMBLES -> "Slow down until you can keep the ball in your control zone, then speed up."
            MetricId.EYES_UP -> "Eyes up: look ahead instead of at the ball."
            MetricId.DRILL_ACCURACY -> "Follow the drill pattern on every bounce before adding speed."
            MetricId.DRIBBLE_HEIGHT -> "Match the drill's dribble height."
            MetricId.TAKEOFF_SYMMETRY -> "Push off both feet at the same time."
            else -> null
        }
    }
}
