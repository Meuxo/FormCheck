package com.meuxo.formcheck.scoring

import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.tracking.geom.Stats
import kotlinx.serialization.Serializable

@Serializable
data class OverallScore(
    /** Mean of the scores of measured, scorable metrics; null if none were measured. */
    val percent: Int?,
    /** How many metrics went into [percent]. */
    val included: Int,
    /** How many scorable metrics this mode reports in total (measured or not). */
    val total: Int
)

object MetricScorer {

    /** Attaches a score to [m] when it is measured and has a target range. */
    fun score(m: Metric, override: TargetRange? = null): Metric {
        val v = m.value ?: return m.copy(score = null)
        val range = override ?: ScoringConfig.targets[m.id] ?: return m.copy(score = null)
        return m.copy(score = ScoringConfig.score(v, range))
    }

    fun scoreAll(metrics: List<Metric>): List<Metric> = metrics.map { score(it) }

    /** Overall = average of measured scored metrics only; states N of M (spec 3f). */
    fun overall(metrics: List<Metric>): OverallScore {
        val scorable = metrics.filter { ScoringConfig.targets.containsKey(it.id) || it.score != null }
        val scored = scorable.mapNotNull { it.score }
        val pct = if (scored.isEmpty()) null else Math.round(scored.average()).toInt()
        return OverallScore(pct, scored.size, scorable.size)
    }

    /** The single lowest-scoring measured metric, for the "biggest fix" card. */
    fun biggestFix(metrics: List<Metric>): Metric? =
        metrics.filter { it.score != null }.minByOrNull { it.score!! }?.takeIf { it.score!! < 100 }
}

/**
 * Consistency across shots (spec 3f): standard deviation of release angle, release height
 * and timing. Needs at least [MIN_SHOTS] shots with that metric measured.
 */
object ConsistencyScorer {
    const val MIN_SHOTS = 3

    fun compute(perShot: List<List<Metric>>): List<Metric> {
        fun spread(source: MetricId, target: MetricId, scale: Double): Metric {
            val values = perShot.mapNotNull { shot -> shot.firstOrNull { it.id == source }?.value }
            if (values.size < MIN_SHOTS) {
                return Metric.notMeasured(target,
                    "Needs $MIN_SHOTS or more shots with ${source.displayName.lowercase()} measured; have ${values.size}.")
            }
            val confs = perShot.mapNotNull { shot -> shot.firstOrNull { it.id == source && it.value != null }?.confidence?.toDouble() }
            val sd = Stats.stdDev(values) * scale
            return MetricScorer.score(
                Metric.measured(target, sd, Stats.mean(confs), null, note = "Across ${values.size} shots")
            )
        }
        return listOf(
            spread(MetricId.RELEASE_ANGLE, MetricId.CONSISTENCY_RELEASE_ANGLE, 1.0),
            spread(MetricId.RELEASE_HEIGHT, MetricId.CONSISTENCY_RELEASE_HEIGHT, 100.0), // m → cm
            spread(MetricId.SHOT_TIMING, MetricId.CONSISTENCY_TIMING, 1.0)
        )
    }
}
