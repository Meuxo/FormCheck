package com.meuxo.formcheck.tracking

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro filter (Casiez, Roussel & Vogel, "1€ Filter: A Simple Speed-based Low-pass
 * Filter for Noisy Input in Interactive Systems", CHI 2012).
 *
 * Time comes from real timestamps, so a variable frame rate is handled correctly.
 */
class OneEuroFilter(
    private val minCutoff: Double,
    private val beta: Double,
    private val dCutoff: Double = TrackingConfig.EURO_D_CUTOFF_HZ
) {
    private var xPrev = Double.NaN
    private var dxPrev = 0.0
    private var tPrev = Double.NaN

    fun reset() {
        xPrev = Double.NaN; dxPrev = 0.0; tPrev = Double.NaN
    }

    /** Filters [x] observed at time [tSec]. Returns the smoothed value. */
    fun filter(x: Double, tSec: Double): Double {
        if (xPrev.isNaN() || tPrev.isNaN()) {
            xPrev = x; tPrev = tSec; dxPrev = 0.0
            return x
        }
        val dt = tSec - tPrev
        if (dt <= 0.0) return xPrev
        val dx = (x - xPrev) / dt
        val edx = lowPass(dx, dxPrev, alpha(dt, dCutoff))
        val cutoff = minCutoff + beta * abs(edx)
        val ex = lowPass(x, xPrev, alpha(dt, cutoff))
        xPrev = ex; dxPrev = edx; tPrev = tSec
        return ex
    }

    private fun lowPass(x: Double, prev: Double, a: Double) = a * x + (1 - a) * prev

    private fun alpha(dt: Double, cutoff: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}

/**
 * Applies a One Euro filter to every coordinate of every landmark of a pose sequence.
 * Missing poses reset the filters, so smoothing never bridges a gap in detection.
 */
class LandmarkSmoother {
    private val img = Array(Lm.COUNT * 2) {
        OneEuroFilter(TrackingConfig.EURO_MIN_CUTOFF_HZ, TrackingConfig.EURO_BETA_PIXELS)
    }
    private val world = Array(Lm.COUNT * 3) {
        OneEuroFilter(TrackingConfig.EURO_MIN_CUTOFF_HZ, TrackingConfig.EURO_BETA_METERS)
    }

    fun reset() {
        img.forEach { it.reset() }
        world.forEach { it.reset() }
    }

    fun smooth(pose: PoseFrame?, tSec: Double): PoseFrame? {
        if (pose == null) {
            reset(); return null
        }
        val outImg = FloatArray(pose.img.size) { img[it].filter(pose.img[it].toDouble(), tSec).toFloat() }
        val outWorld = FloatArray(pose.world.size) { world[it].filter(pose.world[it].toDouble(), tSec).toFloat() }
        return PoseFrame(outImg, outWorld, pose.vis.copyOf())
    }

    companion object {
        fun smoothAll(frames: List<TrackedFrame>): List<TrackedFrame> {
            val s = LandmarkSmoother()
            return frames.map { f -> TrackedFrame(f.index, f.timeUs, s.smooth(f.pose, f.timeSec), f.ball) }
        }
    }
}

/** Time-based derivatives. Velocities always use real timestamps, never frame counts. */
object Kinematics {
    /** Central-difference derivative of [values] at [i] using [timesSec]. NaN at gaps. */
    fun derivative(values: List<Double>, timesSec: List<Double>, i: Int): Double {
        if (values.size < 2) return Double.NaN
        val lo = (i - 1).coerceAtLeast(0)
        val hi = (i + 1).coerceAtMost(values.size - 1)
        val dt = timesSec[hi] - timesSec[lo]
        if (dt <= 0 || values[hi].isNaN() || values[lo].isNaN()) return Double.NaN
        return (values[hi] - values[lo]) / dt
    }
}
