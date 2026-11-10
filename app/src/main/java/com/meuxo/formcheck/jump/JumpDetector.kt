package com.meuxo.formcheck.jump

import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.Side
import com.meuxo.formcheck.tracking.TrackedFrame
import com.meuxo.formcheck.tracking.TrackingConfig
import com.meuxo.formcheck.tracking.geom.Stats

/** One detected flight phase. Times are seconds on the clip timeline. */
data class JumpEvent(
    /** Last frame with both toes on the floor. */
    val lastGroundFrame: Int,
    /** First frame back on the floor. */
    val landingFrame: Int,
    val takeoffSec: Double,
    val landingSec: Double,
    /** Interpolated time each toe left the floor (null if that toe was hidden). */
    val leftToeOffSec: Double?,
    val rightToeOffSec: Double?,
    /** Threshold height (px) above the floor line used to call a toe "off". */
    val thresholdPx: Double
) {
    val flightSec get() = landingSec - takeoffSec
}

/**
 * Finds takeoff and landing from foot landmarks (spec 7b).
 *
 * The floor line is estimated per foot as a high percentile of toe y (most frames are on the
 * floor, and image y grows downward). A toe is "off" when it is more than a small fraction
 * of stature above that line. Crossing times are linearly interpolated between frames
 * (sub-frame precision); the caller corrects for the threshold bias once a metric scale is
 * known (see [JumpPhysics.correctedFlightSec]).
 */
object JumpDetector {

    fun staturePx(frames: List<TrackedFrame>): Double? {
        val spans = frames.mapNotNull { f ->
            val p = f.pose ?: return@mapNotNull null
            if (!p.allVisible(Lm.LEFT_EYE, Lm.RIGHT_EYE)) return@mapNotNull null
            val heels = listOf(Lm.LEFT_HEEL, Lm.RIGHT_HEEL).filter { p.visible(it) }
            if (heels.isEmpty()) return@mapNotNull null
            heels.maxOf { p.px(it).y } - (p.px(Lm.LEFT_EYE).y + p.px(Lm.RIGHT_EYE).y) / 2
        }
        if (spans.size < 3) return null
        return Stats.percentile(spans, 0.9) / TrackingConfig.EYE_HEIGHT_FRACTION_OF_STATURE
    }

    fun detect(frames: List<TrackedFrame>): List<JumpEvent> {
        val stature = staturePx(frames) ?: return emptyList()
        val theta = TrackingConfig.FOOT_LIFT_FRACTION_OF_STATURE * stature
        val n = frames.size
        fun toeY(i: Int, s: Side): Double? = frames[i].pose?.let { p -> if (p.visible(s.toe)) p.px(s.toe).y else null }
        val floorL = Stats.percentile((0 until n).mapNotNull { toeY(it, Side.LEFT) }, 0.85)
        val floorR = Stats.percentile((0 until n).mapNotNull { toeY(it, Side.RIGHT) }, 0.85)
        if (floorL.isNaN() || floorR.isNaN()) return emptyList()

        fun h(i: Int, s: Side): Double? = toeY(i, s)?.let { (if (s == Side.LEFT) floorL else floorR) - it }
        fun both(i: Int): Double? { val l = h(i, Side.LEFT) ?: return null; val r = h(i, Side.RIGHT) ?: return null; return minOf(l, r) }

        val events = ArrayList<JumpEvent>()
        var i = 1
        while (i < n) {
            val prev = both(i - 1)
            val cur = both(i)
            if (prev != null && cur != null && prev <= theta && cur > theta) {
                // Airborne run starts at i. Find its end.
                var j = i
                var broken = false
                while (j + 1 < n) {
                    val nxt = both(j + 1)
                    if (nxt == null) { broken = true; break }
                    if (nxt <= theta) break
                    j++
                }
                if (broken || j + 1 >= n) { i = j + 1; continue }
                val tOff = cross(frames[i - 1].timeSec, prev, frames[i].timeSec, cur, theta)
                val hLand0 = both(j)!!
                val hLand1 = both(j + 1)!!
                val tLand = cross(frames[j].timeSec, hLand0, frames[j + 1].timeSec, hLand1, theta)
                if ((tLand - tOff) * 1000 >= TrackingConfig.MIN_FLIGHT_MS) {
                    events += JumpEvent(
                        lastGroundFrame = i - 1,
                        landingFrame = j + 1,
                        takeoffSec = tOff,
                        landingSec = tLand,
                        leftToeOffSec = toeOff(frames, i, Side.LEFT, ::h, theta),
                        rightToeOffSec = toeOff(frames, i, Side.RIGHT, ::h, theta),
                        thresholdPx = theta
                    )
                }
                i = j + 1
            } else i++
        }
        return events
    }

    private fun cross(t0: Double, h0: Double, t1: Double, h1: Double, level: Double): Double {
        if (h1 == h0) return t1
        val f = ((level - h0) / (h1 - h0)).coerceIn(0.0, 1.0)
        return t0 + f * (t1 - t0)
    }

    /** Last upward crossing of [theta] by one toe at or before frame [airborneAt] (search back 0.5 s). */
    private fun toeOff(
        frames: List<TrackedFrame>, airborneAt: Int, side: Side,
        h: (Int, Side) -> Double?, theta: Double
    ): Double? {
        var k = airborneAt
        val tLimit = frames[airborneAt].timeSec - 0.5
        while (k >= 1 && frames[k].timeSec >= tLimit) {
            val a = h(k - 1, side) ?: return null
            val b = h(k, side) ?: return null
            if (a <= theta && b > theta) return cross(frames[k - 1].timeSec, a, frames[k].timeSec, b, theta)
            k--
        }
        return null
    }
}

object JumpPhysics {
    const val G = 9.80665

    /** Projectile jump height from flight time: h = g·t²/8. */
    fun heightFromFlight(flightSec: Double): Double = G * flightSec * flightSec / 8.0

    fun takeoffVelocity(flightSec: Double): Double = G * flightSec / 2.0

    /**
     * The detector calls a toe "off" only after it has risen [thresholdM] above the floor,
     * which shortens the measured flight on both ends by about thresholdM / v. Adds that
     * time back using the takeoff speed implied by the measured flight (one iteration is
     * enough because the correction is small).
     */
    fun correctedFlightSec(measuredSec: Double, thresholdM: Double): Double {
        if (measuredSec <= 0) return measuredSec
        val v = takeoffVelocity(measuredSec)
        return measuredSec + 2 * thresholdM / v
    }

    /**
     * ±1 frame of timing error on each edge, halved by interpolation, gives a height error of
     * about g·t/(4·fps). Used to state the expected accuracy in the UI.
     */
    fun heightUncertaintyM(flightSec: Double, fps: Double): Double =
        if (fps <= 0) Double.NaN else G * flightSec / (4.0 * fps)
}
