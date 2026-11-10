package com.meuxo.formcheck.scoring

import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.MetricId

data class PracticeDrill(val name: String, val description: String, val reps: String, val dribbleDrillId: String? = null)

/**
 * Drill suggestions for the lowest-scoring *measured* metrics (spec 8). Metrics that were not
 * measured never trigger a drill, so a suggestion always points at something seen in the video.
 */
object DrillRecommendations {
    private val byMetric: Map<MetricId, List<PracticeDrill>> = mapOf(
        MetricId.ELBOW_FLARE to listOf(
            PracticeDrill("Wall elbow alignment", "Stand side-on to a wall with your shooting elbow brushing it and run through your shooting motion, keeping the elbow under the ball.", "3 × 15"),
            PracticeDrill("One-hand form shooting", "From 3–5 ft, shoot with only your shooting hand, elbow pointed at the rim.", "50 makes")
        ),
        MetricId.ELBOW_RELEASE to listOf(
            PracticeDrill("Flick drill", "Lying on your back, flick the ball straight up with a full arm extension so it falls back into your hand.", "3 × 20"),
            PracticeDrill("Follow-through freeze", "Hold your finish, arm fully extended, until the ball reaches the rim.", "Every shot for 10 min")
        ),
        MetricId.ELBOW_SET to listOf(
            PracticeDrill("Set-point pause", "Bring the ball to your set point, pause one second with the elbow in an L, then shoot.", "30 shots")
        ),
        MetricId.KNEE_BEND to listOf(
            PracticeDrill("Chair dip shots", "Lower toward a chair behind you until you barely touch it, then rise straight into the shot.", "3 × 10")
        ),
        MetricId.GUIDE_HAND to listOf(
            PracticeDrill("Guide hand off early", "Take the guide hand off the ball just before release so it cannot push.", "3 × 15 close range"),
            PracticeDrill("Thumb check", "After each shot, check that your guide-hand thumb points up or sideways, not at the rim.", "10 min")
        ),
        MetricId.TRUNK_LEAN to listOf(
            PracticeDrill("Line jumps into shots", "Stand on a court line, jump straight up and land on the same line, then add the shot.", "20 shots")
        ),
        MetricId.BALANCE_DRIFT to listOf(
            PracticeDrill("Pause and shoot", "Catch, hold your stance for one second with weight centred, then shoot.", "30 shots"),
            PracticeDrill("One-foot balance holds", "Hold 30 s on each foot, then shoot from a balanced two-foot base.", "3 holds per foot")
        ),
        MetricId.LANDING_DRIFT to listOf(
            PracticeDrill("Land on the line", "Shoot standing on a line and try to land on it.", "30 shots")
        ),
        MetricId.RELEASE_ANGLE to listOf(
            PracticeDrill("High-arc form shooting", "From 5 ft, shoot over a raised hand or a coach's broom to force more arc.", "50 makes")
        ),
        MetricId.ENTRY_ANGLE to listOf(
            PracticeDrill("High-arc form shooting", "From 5 ft, shoot over a raised hand or a coach's broom to force more arc.", "50 makes")
        ),
        MetricId.FOLLOW_THROUGH to listOf(
            PracticeDrill("Follow-through freeze", "Hold your finish until the ball reaches the rim.", "Every shot for 10 min")
        ),
        MetricId.CONSISTENCY_RELEASE_ANGLE to listOf(PracticeDrill("Close-range rhythm shooting", "Same spot, same routine, 5 ft from the rim; groove an identical finish.", "100 shots")),
        MetricId.CONSISTENCY_RELEASE_HEIGHT to listOf(PracticeDrill("Close-range rhythm shooting", "Same spot, same routine; release at the same height every rep.", "100 shots")),
        MetricId.CONSISTENCY_TIMING to listOf(PracticeDrill("Count-rhythm shooting", "Say \"dip–shoot\" out loud at the same tempo on every rep.", "50 shots")),
        MetricId.STANCE_LOWNESS to listOf(PracticeDrill("Pound dribble", "Low athletic stance, hard dribbles at your side.", "30 s each hand", "pound_right")),
        MetricId.DRIBBLE_HEIGHT to listOf(PracticeDrill("Low/high alternation", "Alternate low and high dribbles to own your dribble height.", "30 s", "low_high")),
        MetricId.BALL_PROTECTION to listOf(PracticeDrill("In and out", "Keep the ball outside your knee with your off arm up.", "30 s", "in_out_right")),
        MetricId.FLUIDITY to listOf(PracticeDrill("Pound dribble", "Steady rhythm at a pace you can hold without fumbling.", "30 s each hand", "pound_left")),
        MetricId.FUMBLES to listOf(PracticeDrill("Pound dribble", "Slow down until every bounce returns to your hand.", "30 s each hand", "pound_right")),
        MetricId.EYES_UP to listOf(PracticeDrill("Crossover with eyes up", "Look at a fixed spot on the wall while crossing over.", "30 s", "crossover")),
        MetricId.DRILL_ACCURACY to listOf(PracticeDrill("Crossover", "Slow, exact crossovers before adding speed.", "30 s", "crossover"))
    )

    fun forMetrics(metrics: List<Metric>, max: Int = 3): List<Pair<Metric, PracticeDrill>> =
        metrics.filter { it.score != null && it.score < 80 }
            .sortedBy { it.score }
            .flatMap { m -> byMetric[m.id].orEmpty().take(1).map { m to it } }
            .distinctBy { it.second.name }
            .take(max)
}
