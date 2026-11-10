package com.meuxo.formcheck.shootaround

import com.meuxo.formcheck.analysis.FormShotAnalyzer
import com.meuxo.formcheck.analysis.ShooterProfile
import com.meuxo.formcheck.analysis.ShotEventDetector
import com.meuxo.formcheck.analysis.ShotEvents
import com.meuxo.formcheck.analysis.SessionAnalyzer
import com.meuxo.formcheck.domain.model.CourtCalibration
import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.domain.model.SessionResult
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.domain.model.ShotOutcome
import com.meuxo.formcheck.domain.model.ShotResult
import com.meuxo.formcheck.jump.JumpDetector
import com.meuxo.formcheck.scoring.ConsistencyScorer
import com.meuxo.formcheck.scoring.MetricScorer
import com.meuxo.formcheck.tracking.Homography
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.ProjectileFit
import com.meuxo.formcheck.tracking.ProjectileFitter
import com.meuxo.formcheck.tracking.RimObs
import com.meuxo.formcheck.tracking.ScaleCalibration
import com.meuxo.formcheck.tracking.Side
import com.meuxo.formcheck.tracking.TimedPoint
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.tracking.TrackedFrame
import com.meuxo.formcheck.tracking.TrackingConfig
import com.meuxo.formcheck.tracking.ViewClassifier
import com.meuxo.formcheck.tracking.geom.Stats
import com.meuxo.formcheck.tracking.geom.Vec2
import kotlin.math.abs

/** One shot attempt found in the ball track. */
data class Attempt(
    val heldFrame: Int,
    val releaseFrame: Int,
    val flightFrames: List<Int>,
    val outcome: ShotOutcome,
    val outcomeReason: String,
    val arc: ProjectileFit?,
    val endFrame: Int
)

/**
 * Shootaround (spec 6): attempts, make/miss, arc, and shot location from the court homography.
 * Every step degrades to an explicit Uncertain / Not measured instead of guessing.
 */
object ShootaroundAnalyzer {

    // ---------------- Attempts and outcome ----------------

    fun detectAttempts(frames: List<TrackedFrame>, rim: RimObs?): List<Attempt> {
        val radii = frames.mapNotNull { it.ball?.takeIf { b -> !b.predicted }?.radius?.toDouble() }
        if (radii.size < 3) return emptyList()
        val r = Stats.median(radii)
        val out = ArrayList<Attempt>()
        var lastHeld = -1
        var lastPose: Pair<Int, com.meuxo.formcheck.tracking.PoseFrame>? = null
        var i = 1
        while (i < frames.size) {
            val f = frames[i]
            f.pose?.let { lastPose = i to it }
            val b = f.ball
            val p = lastPose?.takeIf { f.timeSec - frames[it.first].timeSec < 0.5 }?.second
            if (b != null && !b.predicted && p != null) {
                val nearHand = listOf(Lm.LEFT_WRIST, Lm.RIGHT_WRIST, Lm.LEFT_INDEX, Lm.RIGHT_INDEX)
                    .any { w -> p.visible(w) && p.px(w).distanceTo(b.center) < r * 3.0 }
                if (nearHand) lastHeld = i
                val prev = frames[i - 1].ball
                val rising = prev != null && b.y < prev.y
                val aboveHead = p.visible(Lm.NOSE) && b.y < p.px(Lm.NOSE).y - r
                val recentlyHeld = lastHeld >= 0 && f.timeSec - frames[lastHeld].timeSec < 0.7
                if (!nearHand && rising && aboveHead && recentlyHeld) {
                    val a = follow(frames, lastHeld, lastHeld + 1, rim, r)
                    out += a
                    i = maxOf(a.endFrame, i) + 1
                    lastHeld = -1
                    continue
                }
            }
            i++
        }
        return out
    }

    private fun follow(frames: List<TrackedFrame>, held: Int, release: Int, rim: RimObs?, r: Double): Attempt {
        val t0 = frames[release].timeSec
        val flight = ArrayList<Int>()
        var k = release
        var misses = 0
        var crossing: Pair<Int, Double>? = null      // frame, x at crossing
        var maxHeightY = Double.MAX_VALUE
        while (k < frames.size && frames[k].timeSec - t0 < 2.5) {
            // The outcome is decided within a fixed window after the ball crosses the rim plane.
            if (crossing != null && frames[k].timeSec - frames[crossing.first].timeSec > TrackingConfig.MAKE_CONFIRM_WINDOW_MS / 1000) break
            val b = frames[k].ball
            if (b == null || b.predicted) {
                if (++misses > TrackingConfig.KALMAN_MAX_COAST_FRAMES * 2 && crossing == null && flight.size > 3) break
            } else {
                misses = 0
                // Back in someone's hands (caught rebound, next shot): the flight is over.
                val heldAgain = flight.size > 5 && frames[k].pose?.let { p ->
                    listOf(Lm.LEFT_WRIST, Lm.RIGHT_WRIST).any { w -> p.visible(w) && p.px(w).distanceTo(b.center) < r * 3.0 }
                } == true
                if (heldAgain) break
                flight += k
                maxHeightY = minOf(maxHeightY, b.y.toDouble())
                if (rim != null && crossing == null && flight.size >= 2) {
                    val pb = frames[flight[flight.size - 2]].ball!!
                    if (pb.y < rim.y && b.y >= rim.y) {
                        val f = (rim.y - pb.y) / (b.y - pb.y)
                        crossing = k to (pb.x + (b.x - pb.x) * f).toDouble()
                    }
                }
            }
            k++
        }
        val end = (flight.lastOrNull() ?: release).coerceAtMost(frames.size - 1)
        val arcPts = flight.filter { crossing == null || it <= crossing.first }
            .map { TimedPoint(frames[it].timeSec, frames[it].ball!!.x.toDouble(), frames[it].ball!!.y.toDouble()) }
        val arc = if (arcPts.size >= TrackingConfig.ARC_MIN_POINTS) ProjectileFitter.fit(arcPts, TrackingConfig.ARC_INLIER_RADII * r)?.takeIf { it.ay > 0 } else null
        val (outcome, why) = judge(frames, flight, crossing, rim, r, maxHeightY)
        return Attempt(held, release, flight, outcome, why, arc, end)
    }

    /** Make/miss decision. Anything not clearly one or the other is UNCERTAIN. */
    fun judge(
        frames: List<TrackedFrame>, flight: List<Int>, crossing: Pair<Int, Double>?, rim: RimObs?, r: Double, maxHeightY: Double
    ): Pair<ShotOutcome, String> {
        if (rim == null) return ShotOutcome.UNCERTAIN to "Rim not located."
        val w = rim.width.toDouble()
        if (crossing == null) {
            return if (maxHeightY > rim.y && flight.size > 3) ShotOutcome.MISS to "Ball never reached rim height."
            else ShotOutcome.UNCERTAIN to "Ball lost before it came down past the rim."
        }
        val dx = abs(crossing.second - rim.x)
        if (dx > w / 2 + r) return ShotOutcome.MISS to "Ball came down outside the rim."
        val after = flight.filter { it > crossing.first }
        val inNet = after.any { k ->
            val b = frames[k].ball!!
            b.y > rim.y + 0.2 * w && b.y < rim.y + 2.0 * w && abs(b.x - rim.x) < 0.6 * w
        }
        val bouncedAway = after.any { k -> val b = frames[k].ball!!; abs(b.x - rim.x) > 1.2 * w && b.y < rim.y + w }
        return when {
            inNet && !bouncedAway -> ShotOutcome.MAKE to "Passed down through the rim and into the net area."
            bouncedAway -> ShotOutcome.MISS to "Ball bounced away from the rim."
            dx <= 0.35 * w -> ShotOutcome.UNCERTAIN to "Ball went through the rim plane but was lost below it."
            else -> ShotOutcome.UNCERTAIN to "Ball hit the rim area; result unclear."
        }
    }

    // ---------------- Location ----------------

    data class Location(val court: Vec2?, val reason: String?)

    fun locate(clip: TrackedClip, attempt: Attempt, calibrations: List<CourtCalibration>, jumpStart: Int?): Location {
        val frame = jumpStart ?: attempt.releaseFrame
        if (calibrations.isEmpty()) return Location(null, "Court not calibrated.")
        val cal = calibrations.filter { it.fromFrame <= frame }.maxByOrNull { it.fromFrame }
            ?: return Location(null, "No calibration covers this part of the video.")
        val moved = clip.rimMovedAtFrame
        if (moved != null && frame >= moved && cal.fromFrame < moved) {
            return Location(null, "The phone moved at ${"%.1f".format(clip.frames[moved].timeSec)} s; recalibrate from there.")
        }
        val spec = CourtSpec.of(cal.courtSize)
        val pts = cal.taps.mapNotNull { t -> runCatching { CourtPoint.valueOf(t.courtPointId) }.getOrNull()?.let { t to it } }
        if (pts.size < 4) return Location(null, "Calibration needs at least 4 court points.")
        val h = Homography.estimate(pts.map { it.first.image.toPx(clip.width, clip.height) }, pts.map { it.second.position(spec) })
            ?: return Location(null, "Calibration points are degenerate (e.g. all on one line).")
        val p = clip.frames[frame].pose ?: return Location(null, "Shooter's feet not detected before the shot.")
        if (!p.allVisible(Lm.LEFT_ANKLE, Lm.RIGHT_ANKLE)) return Location(null, "Shooter's feet not visible before the shot.")
        val court = h.map(p.ankleMidPx()) ?: return Location(null, "Feet project outside the calibrated floor plane.")
        if (court.y < -3 || court.y > 47 || abs(court.x) > 28) return Location(null, "Feet project off the half court; check the calibration.")
        return Location(court, null)
    }

    // ---------------- Full analysis ----------------

    fun analyze(clip: TrackedClip, profile: ShooterProfile, calibrations: List<CourtCalibration>): SessionResult {
        val frames = clip.frames
        val warnings = ArrayList<String>()
        if (clip.rim == null) warnings += "Rim not located: tap the rim edges in calibration so makes and misses can be judged."
        if (clip.rimMovedAtFrame != null) warnings += "The phone moved at ${"%.1f".format(frames[clip.rimMovedAtFrame].timeSec)} s. Shot locations after that need a new calibration."
        if (calibrations.isEmpty()) warnings += "Court not calibrated, so shot locations are Not measured."
        warnings += "The pose model is specified for people within about 4 m of the camera; per-shot form numbers for far shots have lower confidence."

        val view = ViewClassifier.classify(frames)
        val hand = profile.hand ?: com.meuxo.formcheck.analysis.HandednessDetector.detect(frames)?.side ?: Side.RIGHT
        val shooterScale = ScaleCalibration.shooterPlane(frames, frames.indices, profile.ballDiameterM, profile.heightM)
        val rimScale = ScaleCalibration.rimPlane(clip.rim)
        val attempts = detectAttempts(frames, clip.rim)
        val events = ShotEventDetector.detect(frames, hand)
        val jumps = JumpDetector.detect(frames)
        val form = FormShotAnalyzer()

        val shots = attempts.mapIndexed { idx, a ->
            val ev = events.minByOrNull { abs(it.releaseFrame - a.releaseFrame) }?.takeIf { abs(it.releaseFrame - a.releaseFrame) <= 6 }
                ?: ShotEvents(a.heldFrame, null, false, null, a.releaseFrame, a.arc, a.flightFrames, radiusOf(frames), a.endFrame)
            val jump = jumps.firstOrNull { j -> j.lastGroundFrame <= a.releaseFrame + 3 && j.landingFrame >= a.releaseFrame - 3 }
            val base = form.shotMetrics(idx, clip, ev, hand, view, shooterScale, rimScale, jump)
            val loc = locate(clip, a, calibrations, jump?.lastGroundFrame)
            val spec = calibrations.lastOrNull()?.let { CourtSpec.of(it.courtSize) }
            base.copy(
                outcome = a.outcome,
                courtX = loc.court?.x, courtY = loc.court?.y,
                zone = if (loc.court != null && spec != null) Zones.classify(loc.court, spec) else null,
                locationReason = loc.reason,
                outcomeReason = a.outcomeReason,
                endFrame = maxOf(base.endFrame, a.endFrame)
            )
        }
        val session = sessionMetrics(shots) + if (shots.size >= ConsistencyScorer.MIN_SHOTS) ConsistencyScorer.compute(shots.map { it.metrics }) else emptyList()
        if (attempts.isEmpty()) warnings += "No shot attempts were found: the ball was not tracked leaving the hands and rising above the head."
        return SessionAnalyzer.finish(clip, SessionType.SHOOTAROUND, view?.view, view?.confidence ?: 0.0, hand, warnings, session, shots,
            headline = session.filter { it.id.name.startsWith("CONSISTENCY") })
    }

    private fun radiusOf(frames: List<TrackedFrame>) =
        Stats.median(frames.mapNotNull { it.ball?.takeIf { b -> !b.predicted }?.radius?.toDouble() }).takeIf { !it.isNaN() } ?: 10.0

    fun sessionMetrics(shots: List<ShotResult>): List<Metric> {
        val makes = shots.count { it.outcome == ShotOutcome.MAKE }
        val misses = shots.count { it.outcome == ShotOutcome.MISS }
        val uncertain = shots.count { it.outcome == ShotOutcome.UNCERTAIN }
        val corrected = shots.count { it.outcomeCorrected }
        var best = 0; var run = 0
        for (s in shots) { if (s.outcome == ShotOutcome.MAKE) { run++; best = maxOf(best, run) } else if (s.outcome == ShotOutcome.MISS) run = 0 }
        val note = buildString {
            if (uncertain > 0) append("$uncertain uncertain not counted. ")
            if (corrected > 0) append("$corrected corrected by you.")
        }.trim().ifEmpty { null }
        val known = makes + misses
        return listOf(
            Metric.measured(MetricId.ATTEMPTS, shots.size.toDouble(), 1.0, null),
            Metric.measured(MetricId.MAKES, makes.toDouble(), if (known == 0) 0.0 else known.toDouble() / shots.size.coerceAtLeast(1), null, note),
            if (known == 0) Metric.notMeasured(MetricId.FG_PERCENT, if (shots.isEmpty()) "No attempts." else "No attempt had a clear result.")
            else Metric.measured(MetricId.FG_PERCENT, 100.0 * makes / known, known.toDouble() / shots.size, null, note),
            Metric.measured(MetricId.LONGEST_STREAK, best.toDouble(), if (uncertain == 0) 1.0 else 0.6, null,
                if (uncertain > 0) "Uncertain shots neither extend nor break a streak." else null)
        ).map { MetricScorer.score(it) }
    }

    /** Recomputes counts after the user corrects an outcome. */
    fun recount(r: SessionResult): SessionResult {
        if (r.type != SessionType.SHOOTAROUND) return r
        val counts = sessionMetrics(r.shots)
        val ids = counts.map { it.id }.toSet()
        return r.copy(metrics = counts + r.metrics.filter { it.id !in ids })
    }

    /** Zone table: (makes, attempts with a known result) per zone. */
    fun byZone(shots: List<ShotResult>): Map<com.meuxo.formcheck.domain.model.CourtZone, Pair<Int, Int>> =
        shots.filter { it.zone != null && it.outcome != ShotOutcome.UNCERTAIN && it.outcome != null }
            .groupBy { it.zone!! }
            .mapValues { (_, l) -> l.count { it.outcome == ShotOutcome.MAKE } to l.size }
}
