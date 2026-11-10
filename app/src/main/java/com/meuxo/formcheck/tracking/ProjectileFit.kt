package com.meuxo.formcheck.tracking

import com.meuxo.formcheck.tracking.geom.LinAlg
import com.meuxo.formcheck.tracking.geom.Vec2
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Ball flight in the image plane: x(t) = x0 + vx·t, y(t) = y0 + vy·t + ay·t².
 * Time t is seconds relative to [t0]. Image y grows down, so gravity gives ay > 0.
 *
 * Parametrising by time (rather than y as a function of x) keeps the fit valid for any
 * horizontal direction and lets velocities come straight from the coefficients.
 */
data class ProjectileFit(
    val t0: Double,
    val x0: Double, val vx: Double,
    val y0: Double, val vy: Double, val ay: Double,
    /** RMS distance (px) of inliers from the curve. */
    val rmsResidualPx: Double,
    val inlierCount: Int,
    val totalCount: Int
) {
    fun pos(t: Double): Vec2 { val s = t - t0; return Vec2(x0 + vx * s, y0 + vy * s + ay * s * s) }
    fun vel(t: Double): Vec2 { val s = t - t0; return Vec2(vx, vy + 2 * ay * s) }
    val apexTime: Double get() = t0 - vy / (2 * ay)
    val apex: Vec2 get() = pos(apexTime)

    /** Later time at which the curve reaches image row [y] on the way down, or null. */
    fun descendingTimeAtY(y: Double): Double? {
        // ay s² + vy s + (y0 - y) = 0
        val disc = vy * vy - 4 * ay * (y0 - y)
        if (ay <= 0 || disc < 0) return null
        val s = (-vy + sqrt(disc)) / (2 * ay)
        return t0 + s
    }
}

data class TimedPoint(val t: Double, val x: Double, val y: Double)

/** RANSAC fit of [ProjectileFit] to noisy ball positions (spec 3e). */
object ProjectileFitter {

    fun fit(
        points: List<TimedPoint>,
        inlierTolPx: Double,
        iterations: Int = 200,
        random: Random = Random(7)
    ): ProjectileFit? {
        if (points.size < 3) return null
        val t0 = points.first().t
        var bestInliers: List<TimedPoint> = emptyList()
        repeat(iterations) {
            val sample = points.shuffled(random).take(3)
            val model = leastSquares(sample, t0) ?: return@repeat
            val inliers = points.filter { residual(model, it) <= inlierTolPx }
            if (inliers.size > bestInliers.size) bestInliers = inliers
        }
        if (bestInliers.size < 3) bestInliers = points
        val model = leastSquares(bestInliers, t0) ?: return null
        val finalInliers = points.filter { residual(model, it) <= inlierTolPx }
        val refit = if (finalInliers.size >= 3) leastSquares(finalInliers, t0) ?: model else model
        val used = if (finalInliers.size >= 3) finalInliers else bestInliers
        val rms = sqrt(used.sumOf { residual(refit, it).let { r -> r * r } } / used.size)
        return refit.copy(rmsResidualPx = rms, inlierCount = used.size, totalCount = points.size)
    }

    private fun residual(m: ProjectileFit, p: TimedPoint): Double = m.pos(p.t).distanceTo(Vec2(p.x, p.y))

    private fun leastSquares(pts: List<TimedPoint>, t0: Double): ProjectileFit? {
        val xRows = pts.map { doubleArrayOf(1.0, it.t - t0) }
        val xSol = LinAlg.leastSquares(xRows, pts.map { it.x }) ?: return null
        val yRows = pts.map { val s = it.t - t0; doubleArrayOf(1.0, s, s * s) }
        val ySol = LinAlg.leastSquares(yRows, pts.map { it.y }) ?: return null
        return ProjectileFit(t0, xSol[0], xSol[1], ySol[0], ySol[1], ySol[2], 0.0, pts.size, pts.size)
    }
}
