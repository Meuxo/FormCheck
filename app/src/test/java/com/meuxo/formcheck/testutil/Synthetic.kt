package com.meuxo.formcheck.testutil

import com.meuxo.formcheck.tracking.BallObs
import com.meuxo.formcheck.tracking.BallSource
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.PoseFrame
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.tracking.TrackedFrame
import com.meuxo.formcheck.tracking.geom.Vec2
import com.meuxo.formcheck.tracking.geom.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds synthetic poses with exactly known joint angles.
 *
 * World coordinates are meters, y DOWN (MediaPipe convention), origin at the hip midpoint.
 * The person faces +x. Camera models:
 *  - SIDE: looks along z, image = (x, y) · scale + origin. Left/right differ only in depth.
 *  - FRONT: looks along x, image = (z, y) · scale + origin.
 */
object Synthetic {
    const val SCALE = 200.0          // px per meter
    val ORIGIN = Vec2(500.0, 500.0)  // hip midpoint in the image
    const val THIGH = 0.45
    const val SHANK = 0.45
    const val UPPER_ARM = 0.30
    const val FOREARM = 0.27

    enum class Camera { SIDE, FRONT }

    data class Posture(
        val kneeDeg: Double = 178.0,
        /** Shooting (right) elbow interior angle. */
        val elbowDeg: Double = 90.0,
        /** Upper arm elevation from straight down, degrees (0 = hanging, 180 = overhead). */
        val shoulderFlexDeg: Double = 60.0,
        /** Vertical offset of the whole body (meters, negative = up), for jumps. */
        val lift: Double = 0.0,
        val trunkLeanDeg: Double = 0.0,
        val visibility: Float = 0.99f
    )

    fun pose(p: Posture, cam: Camera = Camera.SIDE): PoseFrame {
        val j = HashMap<Int, Vec3>()
        // Legs: knee forward of the hip-ankle line. Half-angle β gives interior angle 180 − 2β.
        val beta = Math.toRadians((180.0 - p.kneeDeg) / 2.0)
        val legDrop = (THIGH + SHANK) * cos(beta)
        val floorY = 0.95 + p.lift
        for (side in listOf(-1, 1)) {
            val z = 0.1 * side
            val ankle = Vec3(0.0, floorY - 0.05, z)
            val knee = Vec3(SHANK * sin(beta), ankle.y - SHANK * cos(beta), z)
            val hip = Vec3(0.0, knee.y - THIGH * cos(beta), z)
            val (h, k, a, he, t) = if (side < 0) listOf(Lm.LEFT_HIP, Lm.LEFT_KNEE, Lm.LEFT_ANKLE, Lm.LEFT_HEEL, Lm.LEFT_FOOT_INDEX)
            else listOf(Lm.RIGHT_HIP, Lm.RIGHT_KNEE, Lm.RIGHT_ANKLE, Lm.RIGHT_HEEL, Lm.RIGHT_FOOT_INDEX)
            j[h] = hip; j[k] = knee; j[a] = ankle
            j[he] = Vec3(-0.05, floorY, z); j[t] = Vec3(0.15, floorY, z)
        }
        val hipY = j[Lm.LEFT_HIP]!!.y
        val lean = Math.toRadians(p.trunkLeanDeg)
        fun torso(up: Double, z: Double, fwd: Double = 0.0) = Vec3(fwd + up * sin(lean), hipY - up * cos(lean), z)
        j[Lm.LEFT_SHOULDER] = torso(0.55, -0.2); j[Lm.RIGHT_SHOULDER] = torso(0.55, 0.2)
        j[Lm.NOSE] = torso(0.75, 0.0, 0.08)
        j[Lm.LEFT_EYE] = torso(0.78, -0.03, 0.06); j[Lm.RIGHT_EYE] = torso(0.78, 0.03, 0.06)
        j[Lm.LEFT_EAR] = torso(0.77, -0.07); j[Lm.RIGHT_EAR] = torso(0.77, 0.07)

        // Right (shooting) arm in the sagittal plane.
        val rs = j[Lm.RIGHT_SHOULDER]!!
        val flex = Math.toRadians(p.shoulderFlexDeg)
        val upperDir = Vec3(sin(flex), cos(flex), 0.0)          // 0° = straight down
        val re = rs + upperDir * UPPER_ARM
        // Forearm: rotate the upper-arm direction by (180 − elbow) further "up".
        val fore = flex + Math.toRadians(180.0 - p.elbowDeg)
        val rw = re + Vec3(sin(fore), cos(fore), 0.0) * FOREARM
        j[Lm.RIGHT_ELBOW] = re; j[Lm.RIGHT_WRIST] = rw
        j[Lm.RIGHT_INDEX] = rw + Vec3(sin(fore), cos(fore), 0.0) * 0.08
        // Left (guide) arm hangs slightly forward.
        val ls = j[Lm.LEFT_SHOULDER]!!
        j[Lm.LEFT_ELBOW] = ls + Vec3(0.1, 0.28, 0.0)
        j[Lm.LEFT_WRIST] = ls + Vec3(0.25, 0.45, 0.0)
        j[Lm.LEFT_INDEX] = ls + Vec3(0.3, 0.5, 0.0)

        val img = FloatArray(Lm.COUNT * 2)
        val world = FloatArray(Lm.COUNT * 3)
        val vis = FloatArray(Lm.COUNT)
        val hipMid = Vec3.mid(j[Lm.LEFT_HIP]!!, j[Lm.RIGHT_HIP]!!)
        for (i in 0 until Lm.COUNT) {
            val w = j[i] ?: hipMid
            val px = project(w, cam)
            img[i * 2] = px.x.toFloat(); img[i * 2 + 1] = px.y.toFloat()
            // MediaPipe world landmarks are camera-aligned: x right, y down, z toward the camera.
            val c = when (cam) { Camera.SIDE -> w; Camera.FRONT -> Vec3(w.z, w.y, -w.x) }
            world[i * 3] = c.x.toFloat(); world[i * 3 + 1] = c.y.toFloat(); world[i * 3 + 2] = c.z.toFloat()
            vis[i] = if (j.containsKey(i)) p.visibility else 0f
        }
        return PoseFrame(img, world, vis)
    }

    fun project(w: Vec3, cam: Camera): Vec2 = when (cam) {
        Camera.SIDE -> Vec2(ORIGIN.x + w.x * SCALE, ORIGIN.y + w.y * SCALE)
        Camera.FRONT -> Vec2(ORIGIN.x + w.z * SCALE, ORIGIN.y + w.y * SCALE)
    }

    fun ball(p: Vec2, radiusPx: Double) = BallObs(p.x.toFloat(), p.y.toFloat(), radiusPx.toFloat(), 0.9f, BallSource.LEARNED)

    fun clip(frames: List<TrackedFrame>) = TrackedClip(1000, 1000, frames, rim = null)

    private operator fun <T> List<T>.component6(): T = this[5]
}
