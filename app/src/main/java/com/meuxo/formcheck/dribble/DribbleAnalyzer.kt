package com.meuxo.formcheck.dribble

import com.meuxo.formcheck.analysis.PoseMath
import com.meuxo.formcheck.analysis.SessionAnalyzer
import com.meuxo.formcheck.domain.model.Evidence
import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.domain.model.OverlayHint
import com.meuxo.formcheck.domain.model.OverlayKind
import com.meuxo.formcheck.domain.model.SessionResult
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.scoring.MetricScorer
import com.meuxo.formcheck.scoring.ScoringConfig
import com.meuxo.formcheck.tracking.CameraView
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.PoseFrame
import com.meuxo.formcheck.tracking.Side
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.tracking.TrackedFrame
import com.meuxo.formcheck.tracking.TrackingConfig
import com.meuxo.formcheck.tracking.ViewClassifier
import com.meuxo.formcheck.tracking.geom.Stats
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

data class Bounce(val frame: Int, val timeSec: Double, val x: Double, val hand: Side?)

/** Dribbling metrics (spec 7). Pixel heights are relative to calibrated standing hip height. */
object DribbleAnalyzer {
    const val CALIBRATION_SEC = 3.0

    /** Floor line (px): median of the lowest visible foot point across the clip. */
    fun floorY(frames: List<TrackedFrame>): Double? {
        val ys = frames.mapNotNull { f ->
            val p = f.pose ?: return@mapNotNull null
            listOf(Lm.LEFT_HEEL, Lm.RIGHT_HEEL, Lm.LEFT_FOOT_INDEX, Lm.RIGHT_FOOT_INDEX).filter { p.visible(it) }.maxOfOrNull { p.px(it).y }
        }
        return if (ys.size < 5) null else Stats.median(ys)
    }

    /** Standing hip height above the floor (px) from the first [CALIBRATION_SEC] seconds (spec 7 step 3). */
    fun standingHipPx(frames: List<TrackedFrame>, floor: Double): Double? {
        val t0 = frames.firstOrNull()?.timeSec ?: return null
        val hs = frames.filter { it.timeSec - t0 in 0.5..CALIBRATION_SEC }.mapNotNull { f ->
            f.pose?.takeIf { it.allVisible(Lm.LEFT_HIP, Lm.RIGHT_HIP) }?.let { floor - it.hipMidPx().y }
        }
        return if (hs.size < 5) null else Stats.median(hs).takeIf { it > 0 }
    }

    /**
     * A bounce is a down→up reversal of the ball's vertical motion within a band above the floor
     * (image y grows downward, so it is a local maximum of y).
     */
    fun bounces(frames: List<TrackedFrame>, floor: Double, hipPx: Double, from: Int): List<Bounce> {
        val band = TrackingConfig.BOUNCE_FLOOR_BAND_FRACTION * hipPx
        val out = ArrayList<Bounce>()
        val idx = (from until frames.size).filter { frames[it].ball?.predicted == false }
        for (k in 1 until idx.size - 1) {
            val a = frames[idx[k - 1]].ball!!; val b = frames[idx[k]].ball!!; val c = frames[idx[k + 1]].ball!!
            if (b.y >= a.y && b.y > c.y && floor - b.y <= band) {
                if (out.isNotEmpty() && frames[idx[k]].timeSec - out.last().timeSec < 0.12) continue
                out += Bounce(idx[k], frames[idx[k]].timeSec, b.x.toDouble(), handAt(frames, idx[k]))
            }
        }
        return out
    }

    /** The wrist nearest the ball in the frames right after a bounce (when the hand meets it). */
    private fun handAt(frames: List<TrackedFrame>, i: Int): Side? {
        val votes = HashMap<Side, Int>()
        for (k in i..minOf(i + 8, frames.size - 1)) {
            val p = frames[k].pose ?: continue; val b = frames[k].ball ?: continue
            val sides = Side.entries.filter { p.visible(it.wrist) }
            sides.minByOrNull { p.px(it.wrist).distanceTo(b.center) }?.let { votes[it] = (votes[it] ?: 0) + 1 }
        }
        return votes.maxByOrNull { it.value }?.key
    }

    /** Head pitch (degrees, positive = looking down) from world landmarks: ear midpoint → nose. */
    fun headPitch(p: PoseFrame): Double? {
        if (!p.allVisible(Lm.NOSE, Lm.LEFT_EAR, Lm.RIGHT_EAR)) return null
        val ear = com.meuxo.formcheck.tracking.geom.Vec3.mid(p.w(Lm.LEFT_EAR), p.w(Lm.RIGHT_EAR))
        val d = p.w(Lm.NOSE) - ear
        return Math.toDegrees(atan2(d.y, sqrt(d.x * d.x + d.z * d.z)))
    }

    fun analyze(clip: TrackedClip, drill: Drill?): SessionResult {
        val f = clip.frames
        val warnings = ArrayList<String>()
        val view = ViewClassifier.classify(f)
        val m = ArrayList<Metric>()
        fun nm(id: MetricId, why: String) { m += Metric.notMeasured(id, why) }
        val allIds = listOf(MetricId.STANCE_LOWNESS, MetricId.DRIBBLE_KNEE_ANGLE, MetricId.DRIBBLE_HEIGHT, MetricId.BALL_PROTECTION,
            MetricId.FLUIDITY, MetricId.FUMBLES, MetricId.EYES_UP, MetricId.PACE, MetricId.DRIBBLE_COUNT_LEFT, MetricId.DRIBBLE_COUNT_RIGHT,
            MetricId.DRILL_ACCURACY)
        if (drill == null) warnings += "No drill selected, so drill accuracy and the height target are not measured."
        if (drill?.supported == false) warnings += "${drill.name} is not supported: the tracker follows one ball."

        val floor = floorY(f)
        val hip = floor?.let { standingHipPx(f, it) }
        if (floor == null || hip == null) {
            warnings += "Standing calibration failed: stand still with your whole body and feet in frame for the first ${CALIBRATION_SEC.toInt()} seconds."
            allIds.forEach { nm(it, "No standing calibration (feet or hips not visible in the first 3 s).") }
            return SessionAnalyzer.finish(clip, SessionType.DRIBBLE, view?.view, view?.confidence ?: 0.0, null, warnings, m, emptyList(), m, drillId = drill?.id)
        }
        val start = f.indexOfFirst { it.timeSec - f[0].timeSec >= CALIBRATION_SEC }.takeIf { it >= 0 }
            ?: return SessionAnalyzer.finish(clip, SessionType.DRIBBLE, view?.view, 0.0, null, warnings + "Clip ends during the 3 s calibration.",
                allIds.map { Metric.notMeasured(it, "Clip too short after calibration.") }, emptyList(), emptyList(), drillId = drill?.id)
        val drillFrames = (start until f.size)
        val drillSec = f.last().timeSec - f[start].timeSec
        val ev = Evidence(start, f.size - 1, start + (f.size - start) / 2)

        // Stance lowness and knee angle.
        val lows = drillFrames.mapNotNull { i -> f[i].pose?.takeIf { it.allVisible(Lm.LEFT_HIP, Lm.RIGHT_HIP) }?.let { 100 * (floor - it.hipMidPx().y) / hip } }
        if (lows.size >= 10) m += Metric.measured(MetricId.STANCE_LOWNESS, Stats.mean(lows), coverage(lows.size, drillFrames.count()), ev.copy(overlay = OverlayHint(OverlayKind.JOINT_ANGLE, listOf(Lm.LEFT_HIP, Lm.LEFT_KNEE, Lm.LEFT_ANKLE))))
        else nm(MetricId.STANCE_LOWNESS, "Hips not visible during the drill.")
        val knees = drillFrames.mapNotNull { i -> f[i].pose?.let { PoseMath.kneeEither(it)?.first } }
        if (knees.size >= 10 && view?.view != CameraView.FRONT) m += Metric.measured(MetricId.DRIBBLE_KNEE_ANGLE, Stats.mean(knees), coverage(knees.size, drillFrames.count()) * (view?.confidence ?: 0.5), ev)
        else nm(MetricId.DRIBBLE_KNEE_ANGLE, if (view?.view == CameraView.FRONT) "Knee angle needs a side or angled view." else "Knees not visible during the drill.")

        // Bounces.
        val bs = bounces(f, floor, hip, start)
        if (bs.size < 3) {
            warnings += "Fewer than 3 bounces were detected; the ball may be leaving the frame or blending into the background."
        }
        val ballSeen = drillFrames.count { f[it].ball?.predicted == false }.toDouble() / drillFrames.count().coerceAtLeast(1)

        // Dribble height: apex between consecutive bounces.
        val apexes = bs.zipWithNext().mapNotNull { (a, b) ->
            (a.frame..b.frame).mapNotNull { f[it].ball?.takeIf { x -> !x.predicted }?.y?.toDouble() }.minOrNull()?.let { 100 * (floor - it) / hip }
        }
        if (apexes.size >= 2) m += Metric.measured(MetricId.DRIBBLE_HEIGHT, Stats.mean(apexes), ballSeen, ev.copy(overlay = OverlayHint(OverlayKind.BALL_POSITION)))
        else nm(MetricId.DRIBBLE_HEIGHT, "Not enough complete dribbles tracked.")

        // Ball protection (proxy): ball below the waist, outside the knee on the dribbling side, off arm up.
        val sideOk = view?.view != CameraView.SIDE
        val prot = drillFrames.mapNotNull { i ->
            val p = f[i].pose ?: return@mapNotNull null; val b = f[i].ball?.takeIf { !it.predicted } ?: return@mapNotNull null
            if (!p.allVisible(Lm.LEFT_HIP, Lm.RIGHT_HIP, Lm.LEFT_KNEE, Lm.RIGHT_KNEE, Lm.LEFT_WRIST, Lm.RIGHT_WRIST)) return@mapNotNull null
            val dribbling = Side.entries.minBy { p.px(it.wrist).distanceTo(b.center) }
            val mid = p.hipMidPx().x
            val below = b.y > p.hipMidPx().y
            val outside = abs(b.x - mid) > abs(p.px(dribbling.knee).x - mid)
            val offUp = p.px(dribbling.other.wrist).y < p.px(dribbling.other.hip).y
            below && outside && offUp
        }
        if (!sideOk) nm(MetricId.BALL_PROTECTION, "Needs a front view to see which side of your body the ball is on.")
        else if (prot.size >= 10) m += Metric.measured(MetricId.BALL_PROTECTION, 100.0 * prot.count { it } / prot.size, coverage(prot.size, drillFrames.count()), ev,
            note = "Proxy: no defender is present.")
        else nm(MetricId.BALL_PROTECTION, "Ball and body not tracked together enough.")

        // Fluidity: variation of time between bounces.
        val gaps = bs.zipWithNext { a, b -> b.timeSec - a.timeSec }
        if (gaps.size >= 4) m += Metric.measured(MetricId.FLUIDITY, 100 * Stats.stdDev(gaps) / Stats.mean(gaps), ballSeen,
            Evidence(bs.first().frame, bs.last().frame, bs.first().frame))
        else nm(MetricId.FLUIDITY, "Needs at least 5 bounces.")

        // Fumbles: ball out of the control zone (far from both wrists) for > 0.3 s without bouncing.
        m += fumbles(f, start, hip, bs, ballSeen)

        // Eyes up (proxy).
        val pitches = drillFrames.mapNotNull { f[it].pose?.let { p -> headPitch(p) } }
        if (pitches.size >= 10) m += Metric.measured(MetricId.EYES_UP, 100.0 * pitches.count { it <= TrackingConfig.EYES_UP_MAX_PITCH_DEG } / pitches.size,
            coverage(pitches.size, drillFrames.count()) * 0.7, ev, note = "Proxy from head pitch, not eye tracking.")
        else nm(MetricId.EYES_UP, "Face and ears not visible.")

        // Pace and per-hand counts.
        if (drillSec > 1 && bs.isNotEmpty()) m += Metric.measured(MetricId.PACE, bs.size / drillSec, ballSeen, null)
        else nm(MetricId.PACE, "No bounces detected.")
        val known = bs.count { it.hand != null }
        if (bs.isNotEmpty() && known > 0) {
            m += Metric.measured(MetricId.DRIBBLE_COUNT_LEFT, bs.count { it.hand == Side.LEFT }.toDouble(), known.toDouble() / bs.size, null)
            m += Metric.measured(MetricId.DRIBBLE_COUNT_RIGHT, bs.count { it.hand == Side.RIGHT }.toDouble(), known.toDouble() / bs.size, null)
        } else { nm(MetricId.DRIBBLE_COUNT_LEFT, "Hands not tracked at the bounces."); nm(MetricId.DRIBBLE_COUNT_RIGHT, "Hands not tracked at the bounces.") }

        // Drill accuracy.
        m += drillAccuracy(f, bs, apexes, drill, hip, sideOk, ballSeen)

        val scored = m.map { metric ->
            if (metric.id == MetricId.DRIBBLE_HEIGHT && drill?.heightTarget != null)
                MetricScorer.score(metric, ScoringConfig.dribbleHeightTargets[drill.heightTarget])
            else MetricScorer.score(metric)
        }
        if (view?.view == CameraView.SIDE) warnings += "Side view: drill accuracy and ball protection need a front view."
        return SessionAnalyzer.finish(clip, SessionType.DRIBBLE, view?.view, view?.confidence ?: 0.0, null, warnings, scored, emptyList(), scored, drillId = drill?.id)
    }

    private fun coverage(n: Int, total: Int) = if (total <= 0) 0.0 else (n.toDouble() / total).coerceIn(0.0, 1.0)

    private fun fumbles(f: List<TrackedFrame>, start: Int, hip: Double, bs: List<Bounce>, conf: Double): Metric {
        val far = TrackingConfig.FUMBLE_DISTANCE_HIP_HEIGHTS * hip
        var count = 0; var since = -1.0; var first: Int? = null
        for (i in start until f.size) {
            val p = f[i].pose; val b = f[i].ball?.takeIf { !it.predicted }
            val out = if (p == null || b == null) false else Side.entries.filter { p.visible(it.wrist) }.all { p.px(it.wrist).distanceTo(b.center) > far }
            if (out) {
                if (since < 0) since = f[i].timeSec
                if (f[i].timeSec - since > 0.3 && bs.none { it.timeSec in since..f[i].timeSec }) {
                    count++; if (first == null) first = i; since = Double.MAX_VALUE
                }
            } else since = -1.0
        }
        if (conf < 0.3) return Metric.notMeasured(MetricId.FUMBLES, "Ball tracked in under 30% of the drill.")
        return Metric.measured(MetricId.FUMBLES, count.toDouble(), conf, first?.let { Evidence(it, it, it, OverlayHint(OverlayKind.BALL_POSITION)) })
    }

    private fun drillAccuracy(f: List<TrackedFrame>, bs: List<Bounce>, apexes: List<Double>, drill: Drill?, hip: Double, frontish: Boolean, conf: Double): Metric {
        val id = MetricId.DRILL_ACCURACY
        if (drill == null) return Metric.notMeasured(id, "No drill selected.")
        if (!drill.supported) return Metric.notMeasured(id, "${drill.name} is not supported.")
        if (bs.size < 4) return Metric.notMeasured(id, "Needs at least 4 bounces.")
        fun sideOf(b: Bounce): Int? = f[b.frame].pose?.takeIf { it.allVisible(Lm.LEFT_HIP, Lm.RIGHT_HIP) }?.let { if (b.x > it.hipMidPx().x) 1 else -1 }
        val ok: List<Boolean> = when (drill.pattern) {
            DrillPattern.SAME_HAND -> bs.mapNotNull { b -> b.hand?.let { it == drill.hand } }
            DrillPattern.ALTERNATE_SIDES -> {
                if (!frontish) return Metric.notMeasured(id, "Needs a front view to see the ball switch sides.")
                bs.zipWithNext().mapNotNull { (a, b) -> val sa = sideOf(a); val sb = sideOf(b); if (sa == null || sb == null) null else sa != sb }
            }
            DrillPattern.IN_OUT -> {
                if (!frontish) return Metric.notMeasured(id, "Needs a front view to see the ball move in and out.")
                val lateral = bs.mapNotNull { b -> f[b.frame].pose?.let { abs(b.x - it.hipMidPx().x) } }
                val same = bs.mapNotNull { b -> b.hand?.let { it == drill.hand } }
                lateral.zipWithNext().zipWithNext().map { (p, q) -> (p.second - p.first) * (q.second - q.first) < 0 } + same
            }
            DrillPattern.HEIGHT_ALTERNATE -> {
                val thr = 15.0
                apexes.zipWithNext().zipWithNext().map { (p, q) ->
                    val d1 = p.second - p.first; val d2 = q.second - q.first
                    abs(d1) > thr && abs(d2) > thr && d1 * d2 < 0
                }
            }
        }
        if (ok.size < 3) return Metric.notMeasured(id, "Too few bounces with the needed landmarks visible.")
        return Metric.measured(id, 100.0 * ok.count { it } / ok.size, conf * ok.size / bs.size.coerceAtLeast(1),
            Evidence(bs.first().frame, bs.last().frame, bs.first().frame), note = "Pattern: ${drill.pattern.description}.")
    }
}
