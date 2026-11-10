package com.meuxo.formcheck.scoring

import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.MetricId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ProgressStatsTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private fun ts(d: LocalDate) = d.atStartOfDay(utc).toInstant().toEpochMilli() + 3_600_000

    @Test fun `personal bests respect direction`() {
        val p = listOf(
            MetricPoint(1, 1, MetricId.JUMP_HEIGHT, 40.0, null), MetricPoint(2, 2, MetricId.JUMP_HEIGHT, 52.0, null),
            MetricPoint(1, 1, MetricId.CONSISTENCY_RELEASE_ANGLE, 4.0, 67), MetricPoint(2, 2, MetricId.CONSISTENCY_RELEASE_ANGLE, 1.5, 100),
            MetricPoint(1, 1, MetricId.RELEASE_ANGLE, 60.0, 58), MetricPoint(2, 2, MetricId.RELEASE_ANGLE, 50.0, 100),
            MetricPoint(1, 1, MetricId.RELEASE_HEIGHT, 2.5, null)
        )
        val pb = ProgressStats.personalBests(p)
        assertEquals(52.0, pb[MetricId.JUMP_HEIGHT]!!.value, 0.0)
        assertEquals(1.5, pb[MetricId.CONSISTENCY_RELEASE_ANGLE]!!.value, 0.0)
        assertEquals(50.0, pb[MetricId.RELEASE_ANGLE]!!.value, 0.0)
        // No defensible "best" release height.
        assertNull(pb[MetricId.RELEASE_HEIGHT])
    }

    @Test fun `weekly summary and goal`() {
        val today = LocalDate.of(2026, 11, 11)
        val now = ts(today)
        val old = ts(today.minusDays(21))
        val p = listOf(MetricPoint(1, now, MetricId.MAKES, 30.0, null), MetricPoint(1, now, MetricId.ATTEMPTS, 50.0, null),
            MetricPoint(2, old, MetricId.MAKES, 99.0, null))
        val w = ProgressStats.weekly(p, mapOf(1L to (now to 70), 2L to (old to 90)), goal = 100, now = now, zone = utc)
        assertEquals(1, w.sessions); assertEquals(30, w.makes); assertEquals(50, w.attempts); assertEquals(70, w.avgOverall)
        assertEquals(0.3, w.goalProgress, 1e-9)
    }

    @Test fun `day streak counts back from today or yesterday`() {
        val today = LocalDate.of(2026, 11, 11)
        val days = listOf(today, today.minusDays(1), today.minusDays(2), today.minusDays(5)).map(::ts)
        assertEquals(3, ProgressStats.dayStreak(days, today, utc))
        assertEquals(2, ProgressStats.dayStreak(days.drop(1), today, utc))
        assertEquals(0, ProgressStats.dayStreak(listOf(ts(today.minusDays(3))), today, utc))
    }

    @Test fun `drill recommendations only come from measured low scores`() {
        val ms = listOf(
            MetricScorer.score(Metric.measured(MetricId.ELBOW_FLARE, 30.0, 0.9, null)),   // low
            MetricScorer.score(Metric.measured(MetricId.RELEASE_ANGLE, 50.0, 0.9, null)), // fine
            Metric.notMeasured(MetricId.GUIDE_HAND, "hidden")
        )
        val recs = DrillRecommendations.forMetrics(ms)
        assertEquals(1, recs.size)
        assertEquals(MetricId.ELBOW_FLARE, recs[0].first.id)
        assertTrue(DrillRecommendations.forMetrics(listOf(Metric.notMeasured(MetricId.ELBOW_FLARE, "x"))).isEmpty())
    }
}
