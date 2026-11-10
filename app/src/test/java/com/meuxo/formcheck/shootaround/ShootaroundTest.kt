package com.meuxo.formcheck.shootaround

import com.meuxo.formcheck.analysis.ShooterProfile
import com.meuxo.formcheck.domain.model.CourtCalibration
import com.meuxo.formcheck.domain.model.CourtSize
import com.meuxo.formcheck.domain.model.CourtTap
import com.meuxo.formcheck.domain.model.CourtZone
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.domain.model.PointNorm
import com.meuxo.formcheck.domain.model.ShotOutcome
import com.meuxo.formcheck.testutil.Synthetic
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.RimObs
import com.meuxo.formcheck.tracking.RimSource
import com.meuxo.formcheck.tracking.ScaleCalibration
import com.meuxo.formcheck.tracking.Side
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.tracking.TrackedFrame
import com.meuxo.formcheck.tracking.geom.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShootaroundTest {

    @Test fun `zones on an NBA court`() {
        val c = CourtSpec.of(CourtSize.NBA)
        assertEquals(CourtZone.PAINT, Zones.classify(Vec2(0.0, 10.0), c))
        assertEquals(CourtZone.MID_CENTER, Zones.classify(Vec2(0.0, 25.0), c))
        assertEquals(CourtZone.CORNER_THREE_LEFT, Zones.classify(Vec2(23.0, 5.0), c))
        assertEquals(CourtZone.CORNER_THREE_RIGHT, Zones.classify(Vec2(-23.0, 5.0), c))
        assertEquals(CourtZone.WING_THREE_RIGHT, Zones.classify(Vec2(-20.0, 25.0), c))
        assertEquals(CourtZone.TOP_OF_KEY_THREE, Zones.classify(Vec2(0.0, 30.0), c))
        assertEquals(CourtZone.MID_LEFT, Zones.classify(Vec2(12.0, 14.0), c))
        // A 21 ft corner shot is a two on an NBA court but a three in high school.
        assertEquals(CourtZone.MID_LEFT, Zones.classify(Vec2(21.0, 3.0), c))
        assertEquals(CourtZone.CORNER_THREE_LEFT, Zones.classify(Vec2(21.0, 3.0), CourtSpec.of(CourtSize.HIGH_SCHOOL)))
    }

    private val rim = RimObs(700f, 300f, 60f, 1f, RimSource.USER_TAP)
    private val r = 16.0
    private val g = 9.81 * Synthetic.SCALE

    /** Held for 20 frames, then a flight that reaches rim height at [crossDx] from the rim centre after 0.9 s. */
    private fun shot(startIndex: Int, crossDx: Double, loseAfterCrossing: Boolean): List<TrackedFrame> {
        val out = ArrayList<TrackedFrame>()
        val pose = Synthetic.pose(Synthetic.Posture())
        val hold = pose.px(Lm.RIGHT_WRIST).let { Vec2(it.x, it.y - r - 2) }
        for (k in 0 until 20) out += TrackedFrame(startIndex + k, ((startIndex + k) * 1e6 / 60).toLong(), pose, Synthetic.ball(hold, r))
        val tCross = 0.9
        val vx = (rim.x + crossDx - hold.x) / tCross
        val vy = (rim.y - hold.y - 0.5 * g * tCross * tCross) / tCross
        for (k in 1..80) {
            val t = k / 60.0
            var x = hold.x + vx * t
            val y = hold.y + vy * t + 0.5 * g * t * t
            if (t > tCross && crossDx == 0.0) x = rim.x.toDouble()
            val lost = loseAfterCrossing && t > tCross
            val idx = startIndex + 19 + k
            out += TrackedFrame(idx, (idx * 1e6 / 60).toLong(), pose, if (lost || y > 990) null else Synthetic.ball(Vec2(x, y), r))
        }
        return out
    }

    private fun session(): TrackedClip {
        val frames = ArrayList<TrackedFrame>()
        frames += shot(0, 0.0, false)        // make
        frames += shot(frames.size, 75.0, false)  // miss, outside the rim
        frames += shot(frames.size, 0.0, true)    // through the rim plane, then lost
        return TrackedClip(1000, 1000, frames.mapIndexed { i, f -> TrackedFrame(i, f.timeUs, f.pose, f.ball) }, rim)
    }

    @Test fun `make, miss and uncertain are told apart`() {
        val attempts = ShootaroundAnalyzer.detectAttempts(session().frames, rim)
        assertEquals(3, attempts.size)
        assertEquals(ShotOutcome.MAKE, attempts[0].outcome)
        assertEquals(ShotOutcome.MISS, attempts[1].outcome)
        assertEquals(ShotOutcome.UNCERTAIN, attempts[2].outcome)
        assertNotNull(attempts[0].arc)
    }

    @Test fun `without a rim nothing is called a make or miss`() {
        val attempts = ShootaroundAnalyzer.detectAttempts(session().frames, null)
        assertTrue(attempts.isNotEmpty())
        assertTrue(attempts.all { it.outcome == ShotOutcome.UNCERTAIN })
    }

    @Test fun `session counts exclude uncertain and corrections recount`() {
        val profile = ShooterProfile(Side.RIGHT, 1.8, ScaleCalibration.ballDiameterM(7))
        // Top-down style calibration: image = (500 + 20 x, 900 - 20 y) for court (x, y) in feet.
        val spec = CourtSpec.of(CourtSize.NBA)
        fun tap(p: CourtPoint) = p.position(spec).let { CourtTap(PointNorm((500 + 20 * it.x) / 1000, (900 - 20 * it.y) / 1000), p.name) }
        val cal = CourtCalibration(CourtSize.NBA, listOf(CourtPoint.PAINT_BASELINE_LEFT, CourtPoint.PAINT_BASELINE_RIGHT,
            CourtPoint.FREE_THROW_LEFT, CourtPoint.FREE_THROW_RIGHT).map(::tap))
        val res = ShootaroundAnalyzer.analyze(session(), profile, listOf(cal))
        fun m(id: MetricId) = res.metrics.first { it.id == id }
        assertEquals(3.0, m(MetricId.ATTEMPTS).value!!, 0.0)
        assertEquals(1.0, m(MetricId.MAKES).value!!, 0.0)
        assertEquals(50.0, m(MetricId.FG_PERCENT).value!!, 1e-9)
        assertTrue(m(MetricId.FG_PERCENT).note!!.contains("1 uncertain"))

        // Synthetic ankles sit at image (500, 680) → court (0, 11): in the paint.
        val s0 = res.shots[0]
        assertEquals(0.0, s0.courtX!!, 0.05); assertEquals(11.0, s0.courtY!!, 0.05)
        assertEquals(CourtZone.PAINT, s0.zone)

        val corrected = ShootaroundAnalyzer.recount(res.copy(shots = res.shots.map {
            if (it.index == 2) it.copy(outcome = ShotOutcome.MAKE, outcomeCorrected = true) else it
        }))
        val fg = corrected.metrics.first { it.id == MetricId.FG_PERCENT }
        assertEquals(100.0 * 2 / 3, fg.value!!, 1e-9)
        assertTrue(fg.note!!.contains("corrected"))
    }

    @Test fun `camera movement pauses location`() {
        val clip = session().let { TrackedClip(it.width, it.height, it.frames, it.rim, rimMovedAtFrame = 50) }
        val profile = ShooterProfile(Side.RIGHT, 1.8, ScaleCalibration.ballDiameterM(7))
        val spec = CourtSpec.of(CourtSize.NBA)
        val taps = CourtPoint.entries.take(4).map { p -> p.position(spec).let { CourtTap(PointNorm((500 + 20 * it.x) / 1000, (900 - 20 * it.y) / 1000), p.name) } }
        val res = ShootaroundAnalyzer.analyze(clip, profile, listOf(CourtCalibration(CourtSize.NBA, taps)))
        assertNotNull(res.shots[0].courtX)
        assertNull(res.shots[1].courtX)
        assertTrue(res.shots[1].locationReason!!.contains("moved"))
    }
}
