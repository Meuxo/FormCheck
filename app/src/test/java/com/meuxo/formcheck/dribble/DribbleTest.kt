package com.meuxo.formcheck.dribble

import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.testutil.Synthetic
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.tracking.TrackedFrame
import com.meuxo.formcheck.tracking.geom.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class DribbleTest {
    private val fps = 60.0

    /**
     * Front view. 3 s standing, then a crouch (knee 140°) and a crossover: one bounce every
     * 0.5 s, alternating sides, apex at a known height.
     */
    private fun crossover(seconds: Double = 9.0, alternate: Boolean = true): TrackedClip {
        val stand = Synthetic.pose(Synthetic.Posture(kneeDeg = 178.0), Synthetic.Camera.FRONT)
        val crouch = Synthetic.pose(Synthetic.Posture(kneeDeg = 140.0), Synthetic.Camera.FRONT)
        val floor = stand.px(Lm.LEFT_FOOT_INDEX).y
        val hipStand = floor - stand.hipMidPx().y
        val apexAbove = 0.45 * hipStand
        val n = (seconds * fps).toInt()
        val frames = (0 until n).map { i ->
            val t = i / fps
            if (t < 3.0) return@map TrackedFrame(i, (t * 1e6).toLong(), stand, null)
            val dt = t - 3.0
            val k = (dt / 0.5).toInt()
            val phase = (dt % 0.5) / 0.5                     // 0 at bounce
            val height = apexAbove * (1 - cos(2 * Math.PI * phase)) / 2   // 0 at floor, apex mid-cycle
            val side = if (!alternate || k % 2 == 0) 1 else -1
            val x = crouch.hipMidPx().x + side * 70.0
            val ball = Synthetic.ball(Vec2(x, floor - 12 - height), 12.0)
            TrackedFrame(i, (t * 1e6).toLong(), crouch, ball)
        }
        return TrackedClip(1000, 1000, frames, null)
    }

    @Test fun `bounces, pace, height and crossover accuracy`() {
        val clip = crossover()
        val r = DribbleAnalyzer.analyze(clip, DrillLibrary.byId("crossover"))
        fun m(id: MetricId) = r.metrics.first { it.id == id }
        // 6 s of drill at 2 bounces/s.
        assertEquals(2.0, m(MetricId.PACE).value!!, 0.2)
        assertEquals(100.0, m(MetricId.DRILL_ACCURACY).value!!, 1e-9)
        assertTrue("fluid ${m(MetricId.FLUIDITY).value}", m(MetricId.FLUIDITY).value!! < 5)
        assertEquals(0.0, m(MetricId.FUMBLES).value!!, 0.0)
        // Apex 45% of standing hip height above the floor, plus the 12 px ball radius offset.
        val floor = Synthetic.pose(Synthetic.Posture(), Synthetic.Camera.FRONT).px(Lm.LEFT_FOOT_INDEX).y
        val hipStand = floor - Synthetic.pose(Synthetic.Posture(), Synthetic.Camera.FRONT).hipMidPx().y
        assertEquals(45.0 + 100 * 12 / hipStand, m(MetricId.DRIBBLE_HEIGHT).value!!, 2.0)
        // Crouched at knee 140° the hips are lower than standing.
        assertTrue(m(MetricId.STANCE_LOWNESS).value!! < 100)
        // Front view: knee angle is not measured.
        assertNull(m(MetricId.DRIBBLE_KNEE_ANGLE).value)
    }

    @Test fun `same-side bouncing scores low on a crossover drill`() {
        val r = DribbleAnalyzer.analyze(crossover(alternate = false), DrillLibrary.byId("crossover"))
        assertEquals(0.0, r.metrics.first { it.id == MetricId.DRILL_ACCURACY }.value!!, 1e-9)
    }

    @Test fun `no standing calibration means nothing is measured`() {
        val clip = crossover().let { c -> TrackedClip(c.width, c.height, c.frames.map { TrackedFrame(it.index, it.timeUs, null, it.ball) }, null) }
        val r = DribbleAnalyzer.analyze(clip, DrillLibrary.byId("crossover"))
        assertTrue(r.metrics.all { it.value == null && it.notMeasuredReason != null })
        assertNull(r.overall.percent)
    }

    @Test fun `head pitch is level for the synthetic upright head`() {
        val p = Synthetic.pose(Synthetic.Posture(), Synthetic.Camera.FRONT)
        assertTrue(abs(DribbleAnalyzer.headPitch(p)!!) < 20)
    }
}
