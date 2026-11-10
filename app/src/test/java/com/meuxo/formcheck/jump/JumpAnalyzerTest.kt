package com.meuxo.formcheck.jump

import com.meuxo.formcheck.domain.model.JumpTest
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.testutil.Synthetic
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.tracking.TrackedFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpAnalyzerTest {
    /** Synthetic stature: eyes 1.73 m above the heels / 0.936 → 1.848 m, so the scale is 200 px/m. */
    private val heightM = 1.73 / 0.936

    private fun jumps(fps: Double, flights: List<Double>, landingKnee: Double = 175.0, cam: Synthetic.Camera = Synthetic.Camera.SIDE): TrackedClip {
        val frames = ArrayList<TrackedFrame>()
        var t = 0.0
        var i = 0
        fun add(p: Synthetic.Posture) { frames += TrackedFrame(i++, (t * 1e6).toLong(), Synthetic.pose(p, cam), null); t += 1 / fps }
        repeat((1.0 * fps).toInt()) { add(Synthetic.Posture(kneeDeg = 175.0)) }
        for (fl in flights) {
            // Countermovement down to 110° and back.
            val cmN = (0.6 * fps).toInt()
            repeat(cmN) { k -> val ph = k.toDouble() / cmN; add(Synthetic.Posture(kneeDeg = 175 - 65 * Math.sin(Math.PI * ph))) }
            val v0 = JumpPhysics.takeoffVelocity(fl)
            val t0 = t
            // A tucked landing bends the knees before touchdown (last 30% of the flight).
            while (t - t0 <= fl) { val s = t - t0; add(Synthetic.Posture(kneeDeg = if (s > 0.7 * fl) landingKnee else 175.0, lift = -(v0 * s - 0.5 * JumpPhysics.G * s * s))) }
            repeat((0.15 * fps).toInt()) { add(Synthetic.Posture(kneeDeg = landingKnee)) }
            repeat((1.0 * fps).toInt()) { add(Synthetic.Posture(kneeDeg = 175.0)) }
        }
        return TrackedClip(1000, 1000, frames, null)
    }

    @Test fun `best of three, methods agree, accuracy stated`() {
        val r = JumpAnalyzer.analyze(jumps(120.0, listOf(0.45, 0.55, 0.50)), JumpTest.COUNTERMOVEMENT, heightM, standingReachCm = 230.0)
        assertEquals(3, r.shots.size)
        fun m(id: MetricId) = r.metrics.first { it.id == id }
        val expected = 100 * JumpPhysics.heightFromFlight(0.55)
        assertEquals(expected, m(MetricId.JUMP_HEIGHT).value!!, 1.5)
        assertEquals(550.0, m(MetricId.FLIGHT_TIME).value!!, 12.0)
        assertEquals(expected, m(MetricId.JUMP_HEIGHT_HIP).value!!, expected * 0.1)
        assertTrue(m(MetricId.JUMP_HEIGHT).note!!.contains("agrees"))
        assertTrue(m(MetricId.JUMP_HEIGHT).note!!.contains("±"))
        assertEquals(110.0, m(MetricId.COUNTERMOVEMENT_DEPTH).value!!, 1.0)
        assertEquals(JumpPhysics.takeoffVelocity(0.55), m(MetricId.TAKEOFF_VELOCITY).value!!, 0.05)
        assertEquals(230 + m(MetricId.JUMP_HEIGHT).value!!, m(MetricId.MAX_REACH).value!!, 1e-9)
        // Side view: symmetry needs the front.
        assertNull(m(MetricId.TAKEOFF_SYMMETRY).value)
    }

    @Test fun `tucked landing is flagged and lowers confidence`() {
        val clean = JumpAnalyzer.analyze(jumps(120.0, listOf(0.5)), null, heightM, null).metrics.first { it.id == MetricId.JUMP_HEIGHT }
        val tucked = JumpAnalyzer.analyze(jumps(120.0, listOf(0.5), landingKnee = 120.0), null, heightM, null).metrics.first { it.id == MetricId.JUMP_HEIGHT }
        assertTrue(tucked.note!!.contains("Landing form affected"))
        assertTrue(tucked.confidence < clean.confidence)
    }

    @Test fun `30 fps warns and lowers confidence`() {
        val r30 = JumpAnalyzer.analyze(jumps(30.0, listOf(0.5)), null, heightM, null)
        val r120 = JumpAnalyzer.analyze(jumps(120.0, listOf(0.5)), null, heightM, null)
        assertTrue(r30.warnings.any { it.contains("30 fps") })
        val c30 = r30.metrics.first { it.id == MetricId.JUMP_HEIGHT }; val c120 = r120.metrics.first { it.id == MetricId.JUMP_HEIGHT }
        assertTrue(c30.confidence < c120.confidence)
        // Still within the stated accuracy band at 30 fps.
        assertEquals(100 * JumpPhysics.heightFromFlight(0.5), c30.value!!, 100 * JumpPhysics.heightUncertaintyM(0.5, 30.0) * 2)
    }

    @Test fun `front view measures takeoff symmetry`() {
        val r = JumpAnalyzer.analyze(jumps(120.0, listOf(0.5), cam = Synthetic.Camera.FRONT), null, heightM, null)
        assertEquals(0.0, r.metrics.first { it.id == MetricId.TAKEOFF_SYMMETRY }.value!!, 1.0)
    }
}
