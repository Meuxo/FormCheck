package com.meuxo.formcheck.tracking

import com.meuxo.formcheck.analysis.PoseMath
import com.meuxo.formcheck.testutil.Synthetic
import com.meuxo.formcheck.tracking.geom.Angles
import com.meuxo.formcheck.tracking.geom.LinAlg
import com.meuxo.formcheck.tracking.geom.Stats
import com.meuxo.formcheck.tracking.geom.Vec2
import com.meuxo.formcheck.tracking.geom.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    @Test fun `3D joint angle right angle and straight`() {
        assertEquals(90.0, Angles.jointAngle(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0)), 1e-9)
        assertEquals(180.0, Angles.jointAngle(Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0)), 1e-9)
        assertEquals(60.0, Angles.jointAngle(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), Vec3(0.5, Math.sqrt(3.0) / 2, 0.0)), 1e-9)
    }

    @Test fun `degenerate angle is NaN not zero`() {
        assertTrue(Angles.jointAngle(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)).isNaN())
    }

    @Test fun `angle from vertical and elevation use image y-down`() {
        assertEquals(0.0, Angles.angleFromVerticalUp(Vec2(0.0, 10.0), Vec2(0.0, 0.0)), 1e-9)
        assertEquals(45.0, Angles.angleFromVerticalUp(Vec2(0.0, 10.0), Vec2(10.0, 0.0)), 1e-9)
        assertEquals(45.0, Angles.angleFromVerticalUp(Vec2(0.0, 10.0), Vec2(-10.0, 0.0)), 1e-9)
        // Moving right and up (negative y) at 30°.
        assertEquals(30.0, Angles.elevationDeg(Math.cos(Math.toRadians(30.0)), -Math.sin(Math.toRadians(30.0))), 1e-9)
        assertEquals(-45.0, Angles.elevationDeg(-1.0, 1.0), 1e-9)
    }

    @Test fun `synthetic posture produces the requested knee and elbow angles`() {
        for (knee in listOf(100.0, 120.0, 150.0, 175.0)) for (elbow in listOf(70.0, 90.0, 165.0)) {
            val p = Synthetic.pose(Synthetic.Posture(kneeDeg = knee, elbowDeg = elbow))
            assertEquals(knee, PoseMath.knee(p, Side.LEFT)!!, 1e-4)
            assertEquals(knee, PoseMath.knee(p, Side.RIGHT)!!, 1e-4)
            assertEquals(elbow, PoseMath.elbow(p, Side.RIGHT)!!, 1e-4)
        }
    }

    @Test fun `hidden landmark gives no angle`() {
        val p = Synthetic.pose(Synthetic.Posture(kneeDeg = 120.0))
        p.vis[Lm.LEFT_KNEE] = 0.3f
        assertNull(PoseMath.knee(p, Side.LEFT))
        assertEquals(120.0, PoseMath.kneeEither(p)!!.first, 1e-4)
        assertEquals(1, PoseMath.kneeEither(p)!!.second)
    }

    @Test fun `trunk lean from image`() {
        val p = Synthetic.pose(Synthetic.Posture(trunkLeanDeg = 12.0))
        assertEquals(12.0, PoseMath.trunkLeanImage(p)!!, 1e-4)
    }

    @Test fun `stats`() {
        assertEquals(2.0, Stats.median(listOf(3.0, 1.0, 2.0)), 0.0)
        assertEquals(2.5, Stats.median(listOf(4.0, 1.0, 2.0, 3.0)), 0.0)
        assertEquals(Math.sqrt(2.5), Stats.stdDev(listOf(1.0, 2.0, 3.0, 4.0, 5.0)), 1e-12)
        assertTrue(Stats.stdDev(listOf(1.0)).isNaN())
    }

    @Test fun `linear solve`() {
        val x = LinAlg.solve(arrayOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0)), doubleArrayOf(3.0, 5.0))!!
        assertEquals(0.8, x[0], 1e-12); assertEquals(1.4, x[1], 1e-12)
        assertNull(LinAlg.solve(arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 4.0)), doubleArrayOf(1.0, 2.0)))
    }
}
