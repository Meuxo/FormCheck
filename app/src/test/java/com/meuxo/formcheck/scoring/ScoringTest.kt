package com.meuxo.formcheck.scoring

import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.MetricId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringTest {
    private val r = TargetRange(10.0, 20.0, 10.0, SourceKind.HEURISTIC, "test")

    @Test fun `inside range is 100 and score falls linearly`() {
        assertEquals(100, ScoringConfig.score(15.0, r))
        assertEquals(100, ScoringConfig.score(10.0, r))
        assertEquals(50, ScoringConfig.score(25.0, r))
        assertEquals(50, ScoringConfig.score(5.0, r))
        assertEquals(0, ScoringConfig.score(30.0, r))
        assertEquals(0, ScoringConfig.score(-100.0, r))
    }

    @Test fun `every target range has a source note`() {
        ScoringConfig.targets.values.forEach { assertTrue(it.source.isNotBlank()) }
    }

    @Test fun `overall averages only measured metrics and reports N of M`() {
        val ms = listOf(
            MetricScorer.score(Metric.measured(MetricId.RELEASE_ANGLE, 50.0, 0.9, null)),   // 100
            MetricScorer.score(Metric.measured(MetricId.ELBOW_RELEASE, 145.0, 0.9, null)),  // 50
            Metric.notMeasured(MetricId.ENTRY_ANGLE, "Rim not located"),
            Metric.measured(MetricId.RELEASE_HEIGHT, 2.4, 0.9, null)                        // informational
        )
        val o = MetricScorer.overall(ms)
        assertEquals(75, o.percent)
        assertEquals(2, o.included)
        assertEquals(3, o.total)
        assertNull(ms[3].score)
        assertEquals(MetricId.ELBOW_RELEASE, MetricScorer.biggestFix(ms)!!.id)
    }

    @Test fun `overall is null when nothing was measured`() {
        val o = MetricScorer.overall(listOf(Metric.notMeasured(MetricId.KNEE_BEND, "x")))
        assertNull(o.percent)
        assertEquals(0, o.included)
    }

    @Test fun `consistency uses sample standard deviation and needs three shots`() {
        val shots = listOf(50.0, 52.0, 54.0).map { listOf(Metric.measured(MetricId.RELEASE_ANGLE, it, 0.8, null)) }
        val c = ConsistencyScorer.compute(shots).first { it.id == MetricId.CONSISTENCY_RELEASE_ANGLE }
        assertEquals(2.0, c.value!!, 1e-9)
        assertEquals(100, c.score)
        val two = ConsistencyScorer.compute(shots.take(2)).first { it.id == MetricId.CONSISTENCY_RELEASE_ANGLE }
        assertNull(two.value)
    }
}
