package com.meuxo.formcheck.dribble

import com.meuxo.formcheck.tracking.Side

/** The ball pattern a drill expects, used for "drill accuracy" (spec 7). */
enum class DrillPattern(val description: String) {
    SAME_HAND("Every bounce with the same hand"),
    ALTERNATE_SIDES("Ball switches sides of your body on every bounce"),
    IN_OUT("Same hand; the ball moves in toward your body and back out"),
    HEIGHT_ALTERNATE("Bounces alternate low and high")
}

data class Drill(
    val id: String,
    val name: String,
    val description: String,
    val pattern: DrillPattern,
    /** Expected dribbling hand for one-hand drills. */
    val hand: Side? = null,
    val targetSeconds: Int = 30,
    /** Key into [com.meuxo.formcheck.scoring.ScoringConfig.dribbleHeightTargets]; null = no height target. */
    val heightTarget: String? = "low",
    val supported: Boolean = true
)

object DrillLibrary {
    val drills = listOf(
        Drill("pound_right", "Pound dribble (right)", "Hard, fast dribbles at your side with your right hand. Stay low, eyes up.",
            DrillPattern.SAME_HAND, Side.RIGHT, heightTarget = "waist"),
        Drill("pound_left", "Pound dribble (left)", "Hard, fast dribbles at your side with your left hand. Stay low, eyes up.",
            DrillPattern.SAME_HAND, Side.LEFT, heightTarget = "waist"),
        Drill("crossover", "Crossover", "Low crossovers in front of you, switching hands every bounce.",
            DrillPattern.ALTERNATE_SIDES),
        Drill("between_legs", "Between the legs", "Switch hands with a bounce between your legs, alternating your lead foot.",
            DrillPattern.ALTERNATE_SIDES),
        Drill("behind_back", "Behind the back", "Switch hands by bouncing the ball behind your back.",
            DrillPattern.ALTERNATE_SIDES, heightTarget = "waist"),
        Drill("in_out_right", "In and out (right)", "One hand: push the ball in toward your body, then back out, without switching hands.",
            DrillPattern.IN_OUT, Side.RIGHT, heightTarget = "waist"),
        Drill("figure_8", "Figure 8", "Low dribbles around and between your legs in a figure 8.",
            DrillPattern.ALTERNATE_SIDES),
        Drill("low_high", "Low / high alternation", "Alternate one low dribble (below the knee) with one high dribble (waist).",
            DrillPattern.HEIGHT_ALTERNATE, heightTarget = null),
        Drill("two_ball", "Two-ball pound", "Two balls at once. Not supported yet: the tracker follows one ball.",
            DrillPattern.SAME_HAND, supported = false)
    )

    fun byId(id: String?) = drills.firstOrNull { it.id == id }
}
