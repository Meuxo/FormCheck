package com.meuxo.formcheck.tracking

import com.meuxo.formcheck.testutil.Synthetic
import com.meuxo.formcheck.tracking.ball.BallCandidate
import com.meuxo.formcheck.tracking.ball.BallKalmanTracker
import com.meuxo.formcheck.tracking.ball.ClassicalBallRimDetector
import com.meuxo.formcheck.tracking.ball.RgbImage
import com.meuxo.formcheck.tracking.geom.Stats
import com.meuxo.formcheck.tracking.geom.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TrackingMathTest {

    @Test fun `one euro passes a constant and reduces noise`() {
        val f = OneEuroFilter(1.0, 0.0)
        val rnd = Random(1)
        val raw = ArrayList<Double>(); val out = ArrayList<Double>()
        for (i in 0 until 600) {
            val x = 10.0 + rnd.nextDouble(-1.0, 1.0)
            raw += x; out += f.filter(x, i / 60.0)
        }
        assertEquals(10.0, Stats.mean(out.drop(100)), 0.1)
        assertTrue(Stats.stdDev(out.drop(100)) < Stats.stdDev(raw.drop(100)) * 0.5)
    }

    @Test fun `one euro tracks a ramp with little lag when beta is high`() {
        val slow = OneEuroFilter(1.0, 0.0)
        val fast = OneEuroFilter(1.0, 0.05)
        var lagSlow = 0.0; var lagFast = 0.0
        for (i in 0 until 120) {
            val t = i / 60.0; val x = 300.0 * t
            lagSlow = x - slow.filter(x, t); lagFast = x - fast.filter(x, t)
        }
        assertTrue("beta should cut lag ($lagFast vs $lagSlow)", lagFast < lagSlow * 0.5)
    }

    @Test fun `one euro handles variable frame timing`() {
        val f = OneEuroFilter(1.0, 0.0)
        var t = 0.0
        val rnd = Random(3)
        var last = 0.0
        repeat(400) { t += rnd.nextDouble(0.005, 0.04); last = f.filter(5.0, t) }
        assertEquals(5.0, last, 1e-9)
    }

    @Test fun `view classifier side and front`() {
        val side = Synthetic.pose(Synthetic.Posture(), Synthetic.Camera.SIDE)
        val front = Synthetic.pose(Synthetic.Posture(), Synthetic.Camera.FRONT)
        assertEquals(CameraView.SIDE, ViewClassifier.classifyFrame(side)!!.first)
        assertEquals(CameraView.FRONT, ViewClassifier.classifyFrame(front)!!.first)
    }

    @Test fun `view validity table`() {
        assertEquals(Validity.INVALID, MetricValidity.of(com.meuxo.formcheck.domain.model.MetricId.RELEASE_ANGLE, CameraView.FRONT))
        assertEquals(Validity.VALID, MetricValidity.of(com.meuxo.formcheck.domain.model.MetricId.ELBOW_FLARE, CameraView.FRONT))
        assertEquals(Validity.LOW_CONFIDENCE, MetricValidity.of(com.meuxo.formcheck.domain.model.MetricId.KNEE_BEND, CameraView.ANGLED))
    }

    @Test fun `projectile fit recovers launch angle despite noise and outliers`() {
        val rnd = Random(11)
        val v = 7.0 * Synthetic.SCALE; val ang = Math.toRadians(52.0); val g = 9.81 * Synthetic.SCALE
        val pts = (0 until 40).map { k ->
            val t = k / 60.0
            var x = 300 + v * cos(ang) * t + rnd.nextDouble(-2.0, 2.0)
            var y = 400 - v * sin(ang) * t + 0.5 * g * t * t + rnd.nextDouble(-2.0, 2.0)
            if (k % 9 == 4) { x += 80; y -= 60 }   // outliers (e.g. a hand mis-detected as ball)
            TimedPoint(t, x, y)
        }
        val fit = ProjectileFitter.fit(pts, inlierTolPx = 8.0)!!
        val vel = fit.vel(0.0)
        assertEquals(52.0, com.meuxo.formcheck.tracking.geom.Angles.elevationDeg(vel.x, vel.y), 0.7)
        assertEquals(g / 2, fit.ay, g * 0.03)
        assertTrue(fit.inlierCount >= 34)
        assertTrue(fit.rmsResidualPx < 2.5)
    }

    @Test fun `homography from four and from noisy six points`() {
        // Ground truth: a perspective map.
        val h = Homography(listOf(1.2, 0.1, 30.0, -0.05, 0.9, 50.0, 0.0004, 0.0008, 1.0))
        val src = listOf(Vec2(100.0, 100.0), Vec2(800.0, 120.0), Vec2(780.0, 600.0), Vec2(120.0, 650.0), Vec2(450.0, 380.0), Vec2(300.0, 500.0))
        val dst = src.map { h.map(it)!! }
        val est4 = Homography.estimate(src.take(4), dst.take(4))!!
        src.forEach { p -> assertEquals(0.0, est4.map(p)!!.distanceTo(h.map(p)!!), 1e-6) }
        val rnd = Random(5)
        val noisy = dst.map { Vec2(it.x + rnd.nextDouble(-0.5, 0.5), it.y + rnd.nextDouble(-0.5, 0.5)) }
        val est6 = Homography.estimate(src, noisy)!!
        assertTrue(est6.map(Vec2(500.0, 300.0))!!.distanceTo(h.map(Vec2(500.0, 300.0))!!) < 2.0)
    }

    @Test fun `kalman tracker follows motion and rejects an impossible jump`() {
        val tr = BallKalmanTracker(frameDiagonalPx = 1400.0)
        for (k in 0 until 30) {
            val t = k / 60.0
            val obs = tr.update(t, listOf(BallCandidate(100 + 300 * t, 400.0, 12.0, 0.9, BallSource.LEARNED)))
            assertNotNull(obs)
        }
        // A detection 700 px away one frame later is physically impossible at 4 diagonals/s ceiling? 700px/16ms=42000px/s.
        val t = 30 / 60.0
        val obs = tr.update(t, listOf(BallCandidate(900.0, 100.0, 12.0, 0.9, BallSource.LEARNED)))!!
        assertTrue(obs.predicted)
        assertEquals(100 + 300 * t, obs.x.toDouble(), 3.0)
    }

    @Test fun `classical detector finds a moving orange disc and rejects a static one`() {
        val det = ClassicalBallRimDetector()
        fun frame(cx: Double, cy: Double, r: Double, staticCx: Double): RgbImage {
            val w = 320; val h = 240
            val px = IntArray(w * h) { 0xFF707070.toInt() }
            fun disc(x0: Double, y0: Double) {
                for (y in 0 until h) for (x in 0 until w) if ((x - x0) * (x - x0) + (y - y0) * (y - y0) <= r * r) px[y * w + x] = 0xFFE0661E.toInt()
            }
            disc(cx, cy); disc(staticCx, 200.0)
            return RgbImage(w, h, px)
        }
        det.detectBall(frame(100.0, 80.0, 10.0, 270.0), 0, null)
        val res = det.detectBall(frame(112.0, 76.0, 10.0, 270.0), 16_000, null)
        assertEquals(1, res.size)
        assertEquals(112.0, res[0].x, 1.0); assertEquals(76.0, res[0].y, 1.0); assertEquals(10.0, res[0].radius, 1.0)
    }

    @Test fun `circle fit is exact on a circle`() {
        val pts = (0 until 36).map { val a = it * 10.0 * Math.PI / 180; Vec2(50 + 20 * cos(a), 40 + 20 * sin(a)) }
        val (c, r, rms) = ClassicalBallRimDetector.fitCircle(pts)!!
        assertEquals(50.0, c.x, 1e-6); assertEquals(40.0, c.y, 1e-6); assertEquals(20.0, r, 1e-6); assertEquals(0.0, rms, 1e-6)
    }
}
