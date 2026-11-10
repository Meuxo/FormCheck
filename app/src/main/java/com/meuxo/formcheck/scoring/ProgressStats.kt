package com.meuxo.formcheck.scoring

import com.meuxo.formcheck.domain.model.MetricId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/** A stored measurement, independent of Room so these stats are unit-testable. */
data class MetricPoint(val sessionId: Long, val timestamp: Long, val id: MetricId, val value: Double, val score: Int?)

data class WeeklySummary(val sessions: Int, val makes: Int, val attempts: Int, val avgOverall: Int?, val goalProgress: Double)

object ProgressStats {
    private val higherIsBetter = setOf(
        MetricId.JUMP_HEIGHT, MetricId.JUMP_HEIGHT_HIP, MetricId.FLIGHT_TIME, MetricId.TAKEOFF_VELOCITY, MetricId.MAX_REACH,
        MetricId.MAKES, MetricId.FG_PERCENT, MetricId.LONGEST_STREAK, MetricId.PACE, MetricId.FOLLOW_THROUGH,
        MetricId.EYES_UP, MetricId.BALL_PROTECTION, MetricId.DRILL_ACCURACY
    )
    private val lowerIsBetter = setOf(
        MetricId.CONSISTENCY_RELEASE_ANGLE, MetricId.CONSISTENCY_RELEASE_HEIGHT, MetricId.CONSISTENCY_TIMING,
        MetricId.FUMBLES, MetricId.FLUIDITY, MetricId.BALANCE_DRIFT, MetricId.LANDING_DRIFT
    )

    /** Whether "best" is meaningful for this metric, and how to rank. Null = no defensible best. */
    fun rank(id: MetricId): ((MetricPoint) -> Double)? = when {
        id in higherIsBetter -> { p -> p.value }
        id in lowerIsBetter -> { p -> -p.value }
        ScoringConfig.targets.containsKey(id) -> { p -> (p.score ?: 0).toDouble() }
        else -> null
    }

    fun personalBests(points: List<MetricPoint>): Map<MetricId, MetricPoint> =
        points.groupBy { it.id }.mapNotNull { (id, l) -> rank(id)?.let { r -> l.maxByOrNull(r)?.let { id to it } } }.toMap()

    fun weekly(
        points: List<MetricPoint>, sessionOverall: Map<Long, Pair<Long, Int?>>, goal: Int,
        now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()
    ): WeeklySummary {
        val wf = WeekFields.of(Locale.getDefault())
        fun weekOf(ts: Long) = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().let { it.get(wf.weekBasedYear()) * 100 + it.get(wf.weekOfWeekBasedYear()) }
        val thisWeek = weekOf(now)
        val sessions = sessionOverall.filter { weekOf(it.value.first) == thisWeek }
        val wp = points.filter { weekOf(it.timestamp) == thisWeek && it.sessionId in sessions.keys }
        val makes = wp.filter { it.id == MetricId.MAKES }.sumOf { it.value }.toInt()
        val attempts = wp.filter { it.id == MetricId.ATTEMPTS }.sumOf { it.value }.toInt()
        val scores = sessions.values.mapNotNull { it.second }
        return WeeklySummary(sessions.size, makes, attempts, if (scores.isEmpty()) null else Math.round(scores.average()).toInt(),
            if (goal <= 0) 0.0 else (makes.toDouble() / goal).coerceAtMost(1.0))
    }

    /** Consecutive days, ending today or yesterday, with at least one session. */
    fun dayStreak(timestamps: List<Long>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Int {
        val days = timestamps.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.toSet()
        var d = if (today in days) today else today.minusDays(1)
        var n = 0
        while (d in days) { n++; d = d.minusDays(1) }
        return n
    }
}
