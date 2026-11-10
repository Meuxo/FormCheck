package com.meuxo.formcheck.analysis

import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.jump.JumpDetector
import com.meuxo.formcheck.jump.JumpPhysics
import com.meuxo.formcheck.testutil.Synthetic
import com.meuxo.formcheck.testutil.Synthetic.Posture
import com.meuxo.formcheck.tracking.CameraView
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.ScaleCalibration
import com.meuxo.formcheck.tracking.Side
import com.meuxo.formcheck.tracking.TrackedFrame
import com.meuxo.formcheck.tracking.geom.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FormShotAnalyzerTest {

    private val fps = 60.0
    private val ballR = ScaleCalibration.ballDiameterM(7) / 2 * Synthetic.SCALE
    private val launchDeg = 52.0
    private val launchSpeed = 7.0
    private val trueRelease = 62

    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f.coerceIn(0.0, 1.0)

    private fun posture(i: Int): Posture = when {
        i < 20 -> Posture(kneeDeg = 175.0, shoulderFlexDeg = 40.0, elbowDeg = 80.0)
        i < 36 -> { val f = (i - 20) / 15.0; Posture(kneeDeg = lerp(175.0, 120.0, f), shoulderFlexDeg = lerp(40.0, 25.0, f), elbowDeg = 80.0) }
        i < 50 -> { val f = (i - 36) / 14.0; Posture(kneeDeg = lerp(120.0, 178.0, f), shoulderFlexDeg = lerp(25.0, 150.0, f), elbowDeg = if (i < 44) lerp(80.0, 75.0, (i - 36) / 8.0) else lerp(75.0, 100.0, (i - 44) / 6.0)) }
        i < 60 -> { val f = (i - 50) / 10.0; Posture(kneeDeg = 178.0, shoulderFlexDeg = lerp(150.0, 165.0, f), elbowDeg = lerp(100.0, 170.0, f)) }
        i < 100 -> Posture(kneeDeg = 178.0, shoulderFlexDeg = 165.0, elbowDeg = 170.0)
        else -> { val f = (i - 100) / 20.0; Posture(kneeDeg = 178.0, shoulderFlexDeg = lerp(165.0, 40.0, f), elbowDeg = 170.0) }
    }

    private fun heldBall(p: com.meuxo.formcheck.tracking.PoseFrame): Vec2 {
        val idx = p.px(Lm.RIGHT_INDEX)
        return Vec2(idx.x, idx.y - ballR - 2)
    }

    private fun buildShot(cam: Synthetic.Camera = Synthetic.Camera.SIDE, withBall: Boolean = true): List<TrackedFrame> {
        val frames = ArrayList<TrackedFrame>()
        var releasePos: Vec2? = null
        for (i in 0 until 130) {
            val p = Synthetic.pose(posture(i), cam)
            val t = i / fps
            val ball = if (!withBall) null else if (i < trueRelease) Synthetic.ball(heldBall(p), ballR) else {
                if (releasePos == null) releasePos = heldBall(Synthetic.pose(posture(trueRelease - 1), cam))
                val s = (i - trueRelease + 1) / fps
                val v = launchSpeed * Synthetic.SCALE
                val g = 9.81 * Synthetic.SCALE
                val a = Math.toRadians(launchDeg)
                val pos = Vec2(releasePos!!.x + v * cos(a) * s, releasePos!!.y - v * sin(a) * s + 0.5 * g * s * s)
                if (pos.y > 1200) null else Synthetic.ball(pos, ballR)
            }
            frames += TrackedFrame(i, (t * 1_000_000).toLong(), p, ball)
        }
        return frames
    }

    private val profile = ShooterProfile(Side.RIGHT, heightM = 1.80, ballDiameterM = ScaleCalibration.ballDiameterM(7))

    @Test fun `side view shot is measured with known answers`() {
        val res = FormShotAnalyzer().analyze(Synthetic.clip(buildShot()), profile)
        assertEquals(CameraView.SIDE, res.view!!.view)
        assertEquals(1, res.shots.size)
        val shot = res.shots[0]
        val rel = shot.releaseFrame!!
        assertTrue("release frame $rel should be just after true release", rel in trueRelease..trueRelease + 3)
        fun v(id: MetricId) = shot.metrics.first { it.id == id }

        assertEquals(120.0, v(MetricId.KNEE_BEND).value!!, 0.6)
        assertEquals(75.0, v(MetricId.ELBOW_SET).value!!, 0.6)
        assertEquals(170.0, v(MetricId.ELBOW_RELEASE).value!!, 0.6)
        // Angle at the detected release frame is slightly flatter than at the true launch.
        assertEquals(launchDeg, v(MetricId.RELEASE_ANGLE).value!!, 3.0)
        assertTrue(v(MetricId.RELEASE_ANGLE).confidence > 0.5)
        assertNotNull(v(MetricId.RELEASE_ANGLE).score)
        assertEquals((rel - 35) / fps * 1000, v(MetricId.SHOT_TIMING).value!!, 20.0)
        assertTrue(v(MetricId.FOLLOW_THROUGH).value!! > 500)
        assertEquals(0.0, v(MetricId.BALANCE_DRIFT).value!!, 0.5)

        // Release height: floor (heels) minus ball centre, scaled by the ball (≈ 200 px/m).
        assertEquals(690.0, 500 + 0.95 * Synthetic.SCALE, 1e-9)
        assertTrue(v(MetricId.RELEASE_HEIGHT).value!! in 2.0..3.0)

        // Front-view-only and rim-dependent metrics are Not measured, with a reason.
        assertNull(v(MetricId.ELBOW_FLARE).value)
        assertNotNull(v(MetricId.ELBOW_FLARE).notMeasuredReason)
        assertNull(v(MetricId.ENTRY_ANGLE).value)
        assertTrue(v(MetricId.ENTRY_ANGLE).notMeasuredReason!!.contains("Rim"))
    }

    @Test fun `entry angle at a known rim matches projectile physics`() {
        val frames = buildShot()
        // Put the rim where the true flight is 1.0 s after launch (descending branch).
        val start = heldBall(Synthetic.pose(posture(trueRelease - 1)))
        val v = launchSpeed * Synthetic.SCALE; val g = 9.81 * Synthetic.SCALE; val a = Math.toRadians(launchDeg)
        val t = 1.0
        val rimX = start.x + v * cos(a) * t; val rimY = start.y - v * sin(a) * t + 0.5 * g * t * t
        val clip = com.meuxo.formcheck.tracking.TrackedClip(1000, 1000, frames,
            com.meuxo.formcheck.tracking.RimObs(rimX.toFloat(), rimY.toFloat(), 91f, 1f, com.meuxo.formcheck.tracking.RimSource.USER_TAP))
        val m = FormShotAnalyzer().analyze(clip, profile).shots[0].metrics.first { it.id == MetricId.ENTRY_ANGLE }
        val expected = Math.toDegrees(kotlin.math.atan((9.81 * t - launchSpeed * sin(a)) / (launchSpeed * cos(a))))
        assertEquals(expected, m.value!!, 1.0)
        assertNotNull(m.score)
    }

    @Test fun `front view disables side-only metrics and measures elbow alignment`() {
        val res = FormShotAnalyzer().analyze(Synthetic.clip(buildShot(Synthetic.Camera.FRONT)), profile)
        assertEquals(CameraView.FRONT, res.view!!.view)
        val m = res.shots.first().metrics
        assertNull(m.first { it.id == MetricId.KNEE_BEND }.value)
        assertNull(m.first { it.id == MetricId.RELEASE_ANGLE }.value)
        // In this synthetic front view the shooting arm moves in the sagittal plane, so the
        // forearm projects straight up or down: tilt from vertical is 0 or 180 → near-vertical.
        val flare = m.first { it.id == MetricId.ELBOW_FLARE }
        assertNotNull(flare.value)
    }

    @Test fun `no ball means release metrics are not measured, never guessed`() {
        val res = FormShotAnalyzer().analyze(Synthetic.clip(buildShot(withBall = false)), profile)
        val m = res.shots.first().metrics
        for (id in listOf(MetricId.ELBOW_RELEASE, MetricId.RELEASE_ANGLE, MetricId.SHOT_TIMING, MetricId.FOLLOW_THROUGH)) {
            val metric = m.first { it.id == id }
            assertNull("$id must not be measured", metric.value)
            assertNull(metric.score)
            assertNotNull(metric.notMeasuredReason)
        }
        assertEquals(120.0, m.first { it.id == MetricId.KNEE_BEND }.value!!, 0.6)
        assertTrue(res.warnings.any { it.contains("ball", ignoreCase = true) })
    }

    @Test fun `left hand profile mismatch is warned`() {
        val res = FormShotAnalyzer().analyze(Synthetic.clip(buildShot()), profile.copy(hand = Side.LEFT))
        assertTrue(res.warnings.any { it.contains("left-handed") })
    }

    @Test fun `jump flight time gives h = g t squared over 8`() {
        val flight = 0.50
        val v0 = JumpPhysics.takeoffVelocity(flight)
        val frames = (0 until 120).map { i ->
            val t = i / fps
            val tj = t - 0.6
            val lift = if (tj in 0.0..flight) -(v0 * tj - 0.5 * JumpPhysics.G * tj * tj) else 0.0
            TrackedFrame(i, (t * 1e6).toLong(), Synthetic.pose(Posture(kneeDeg = 175.0, lift = lift)), null)
        }
        val jumps = JumpDetector.detect(frames)
        assertEquals(1, jumps.size)
        val j = jumps[0]
        // The 1.5%-of-stature threshold shortens raw flight time; the correction restores it.
        val thrM = j.thresholdPx / Synthetic.SCALE
        val corrected = JumpPhysics.correctedFlightSec(j.flightSec, thrM)
        assertEquals(flight, corrected, 0.012)
        assertEquals(JumpPhysics.heightFromFlight(flight), JumpPhysics.heightFromFlight(corrected), 0.01)
    }
}
