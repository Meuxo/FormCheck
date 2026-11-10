package com.meuxo.formcheck.tracking.ball

import com.meuxo.formcheck.tracking.BallObs
import com.meuxo.formcheck.tracking.BallSource
import com.meuxo.formcheck.tracking.TrackingConfig
import com.meuxo.formcheck.tracking.geom.Vec2
import kotlin.math.sqrt

/**
 * Constant-velocity Kalman filter on the ball centre (state x, y, vx, vy) with an optional
 * known gravity term, real-time dt, chi-square innovation gating and a hard physical speed
 * limit. Detections that imply an impossible jump are rejected; the tracker coasts on its
 * prediction for a few frames and then drops the track.
 *
 * Process noise is deliberately high (hands accelerate the ball hard during a dribble or
 * shot) so the filter trusts detections and mainly serves to reject outliers and bridge
 * short gaps.
 */
class BallKalmanTracker(
    frameDiagonalPx: Double,
    /** Pixels per meter at the ball's depth, if known. Enables gravity and a metric speed limit. */
    private val pxPerMeter: Double? = null
) {
    private val maxSpeedPx = pxPerMeter?.let { TrackingConfig.MAX_BALL_SPEED_MPS * it }
        ?: (TrackingConfig.MAX_BALL_SPEED_DIAGONALS_PER_SEC * frameDiagonalPx)
    private val accelNoise = 2.5 * frameDiagonalPx   // px/s², HEURISTIC
    private val gravityPx = pxPerMeter?.let { 9.81 * it } ?: 0.0

    private var x = DoubleArray(4)
    private var p = Array(4) { DoubleArray(4) }
    private var lastT = Double.NaN
    private var coast = 0
    private var radius = 0.0
    var active = false
        private set

    fun predictedPosition(tSec: Double): Vec2? {
        if (!active) return null
        val dt = tSec - lastT
        return Vec2(x[0] + x[2] * dt, x[1] + x[3] * dt + 0.5 * gravityPx * dt * dt)
    }

    /**
     * Advances to [tSec] and incorporates the best acceptable candidate. Returns the
     * tracked observation for this frame (detected or predicted) or null if there is no track.
     */
    fun update(tSec: Double, candidates: List<BallCandidate>, minStartScore: Double = 0.3): BallObs? {
        if (!active) {
            val c = candidates.firstOrNull { it.score >= minStartScore } ?: return null
            start(c, tSec)
            return obs(c.score, c.source, predicted = false)
        }
        val dt = tSec - lastT
        if (dt <= 0) return obs(0.5, BallSource.PREDICTED, predicted = true)
        predict(dt)
        lastT = tSec

        val r = measurementNoise()
        var best: BallCandidate? = null
        var bestD2 = Double.MAX_VALUE
        for (c in candidates) {
            val dx = c.x - x[0]
            val dy = c.y - x[1]
            val sxx = p[0][0] + r; val syy = p[1][1] + r; val sxy = p[0][1]
            val det = sxx * syy - sxy * sxy
            if (det <= 0) continue
            val d2 = (dx * dx * syy - 2 * dx * dy * sxy + dy * dy * sxx) / det
            if (d2 > TrackingConfig.KALMAN_GATE_CHI2) continue
            val impliedSpeed = sqrt(dx * dx + dy * dy) / dt
            // Measured relative to the prediction; a real ball cannot add this much speed.
            if (impliedSpeed > maxSpeedPx) continue
            if (d2 < bestD2) { bestD2 = d2; best = c }
        }
        val chosen = best
        if (chosen == null) {
            coast++
            if (coast > TrackingConfig.KALMAN_MAX_COAST_FRAMES) {
                active = false
                // A fresh, strong detection elsewhere may restart the track immediately.
                val restart = candidates.firstOrNull { it.score >= minStartScore }
                if (restart != null) { start(restart, tSec); return obs(restart.score, restart.source, false) }
                return null
            }
            return obs(0.3, BallSource.PREDICTED, predicted = true)
        }
        correct(chosen.x, chosen.y, r)
        radius = 0.7 * radius + 0.3 * chosen.radius
        coast = 0
        return obs(chosen.score, chosen.source, predicted = false)
    }

    private fun start(c: BallCandidate, t: Double) {
        x = doubleArrayOf(c.x, c.y, 0.0, 0.0)
        val big = maxSpeedPx * maxSpeedPx / 4
        p = arrayOf(
            doubleArrayOf(c.radius * c.radius, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, c.radius * c.radius, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, big, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, big)
        )
        radius = c.radius
        lastT = t
        coast = 0
        active = true
    }

    private fun measurementNoise(): Double { val s = 0.25 * radius.coerceAtLeast(2.0); return s * s }

    private fun predict(dt: Double) {
        x[0] += x[2] * dt
        x[1] += x[3] * dt + 0.5 * gravityPx * dt * dt
        x[3] += gravityPx * dt
        val f = arrayOf(
            doubleArrayOf(1.0, 0.0, dt, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0, dt),
            doubleArrayOf(0.0, 0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        )
        val q2 = accelNoise * accelNoise
        val dt2 = dt * dt; val dt3 = dt2 * dt / 2; val dt4 = dt2 * dt2 / 4
        val q = arrayOf(
            doubleArrayOf(dt4 * q2, 0.0, dt3 * q2, 0.0),
            doubleArrayOf(0.0, dt4 * q2, 0.0, dt3 * q2),
            doubleArrayOf(dt3 * q2, 0.0, dt2 * q2, 0.0),
            doubleArrayOf(0.0, dt3 * q2, 0.0, dt2 * q2)
        )
        val fp = mul(f, p)
        val fpft = mul(fp, transpose(f))
        p = Array(4) { i -> DoubleArray(4) { j -> fpft[i][j] + q[i][j] } }
    }

    private fun correct(zx: Double, zy: Double, r: Double) {
        val sxx = p[0][0] + r; val syy = p[1][1] + r; val sxy = p[0][1]; val syx = p[1][0]
        val det = sxx * syy - sxy * syx
        if (det <= 0) return
        val inv = arrayOf(doubleArrayOf(syy / det, -sxy / det), doubleArrayOf(-syx / det, sxx / det))
        // K = P Hᵀ S⁻¹, H selects the first two state entries.
        val k = Array(4) { i -> DoubleArray(2) { j -> p[i][0] * inv[0][j] + p[i][1] * inv[1][j] } }
        val yx = zx - x[0]; val yy = zy - x[1]
        for (i in 0 until 4) x[i] += k[i][0] * yx + k[i][1] * yy
        val newP = Array(4) { i -> DoubleArray(4) { j -> p[i][j] - (k[i][0] * p[0][j] + k[i][1] * p[1][j]) } }
        p = newP
    }

    private fun obs(conf: Double, source: BallSource, predicted: Boolean) = BallObs(
        x = x[0].toFloat(), y = x[1].toFloat(), radius = radius.toFloat(),
        confidence = conf.toFloat(), source = if (predicted) BallSource.PREDICTED else source,
        predicted = predicted
    )

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(a.size) { i -> DoubleArray(b[0].size) { j -> var s = 0.0; for (k in b.indices) s += a[i][k] * b[k][j]; s } }

    private fun transpose(a: Array<DoubleArray>) = Array(a[0].size) { i -> DoubleArray(a.size) { j -> a[j][i] } }
}
