package com.meuxo.formcheck.jump

import com.meuxo.formcheck.analysis.PoseMath
import com.meuxo.formcheck.analysis.SessionAnalyzer
import com.meuxo.formcheck.domain.model.Evidence
import com.meuxo.formcheck.domain.model.JumpTest
import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.domain.model.OverlayHint
import com.meuxo.formcheck.domain.model.OverlayKind
import com.meuxo.formcheck.domain.model.SessionResult
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.domain.model.ShotResult
import com.meuxo.formcheck.scoring.MetricScorer
import com.meuxo.formcheck.tracking.CameraView
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.MetricValidity
import com.meuxo.formcheck.tracking.ScaleCalibration
import com.meuxo.formcheck.tracking.Side
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.tracking.TrackingConfig
import com.meuxo.formcheck.tracking.Validity
import com.meuxo.formcheck.tracking.ViewClassifier
import kotlin.math.abs

/**
 * Vertical jump test (spec 7b). Two independent methods, cross-checked:
 *  1. Flight time (primary): h = g·t²/8, takeoff/landing from toe landmarks with sub-frame
 *     interpolation and threshold-bias correction.
 *  2. Hip displacement (secondary): peak rise of the hip midpoint above its takeoff height,
 *     scaled by the user's height.
 * Each attempt is a "shot" entry (for the attempt list); the session headline is the best one.
 */
object JumpAnalyzer {

    fun analyze(clip: TrackedClip, test: JumpTest?, heightM: Double?, standingReachCm: Double?): SessionResult {
        val f = clip.frames
        val warnings = ArrayList<String>()
        val view = ViewClassifier.classify(f)
        val fps = clip.fps
        val fpsFactor = when {
            fps >= 110 -> 1.0
            fps >= 55 -> 0.85
            else -> 0.5
        }
        if (fps < 55) warnings += "Only ${fps.toInt()} fps: each takeoff/landing frame is ${(1000 / fps).toInt()} ms, so jump height is less certain. Use 60 fps or more."
        if (clip.timeScale != 1.0) warnings += "Slow-motion video detected; timestamps were converted to real time."
        val scale = heightM?.let { ScaleCalibration.fromUserHeight(f, f.indices, it) }
        if (heightM == null) warnings += "Height not set: the hip-rise cross-check and threshold correction are unavailable."

        val jumps = JumpDetector.detect(f)
        if (jumps.isEmpty()) warnings += "No jump found: both feet must leave the floor and the feet must stay visible."
        val attempts = jumps.mapIndexed { i, j -> attemptMetrics(i, clip, j, view?.view, scale?.pxPerMeter, fpsFactor, standingReachCm) }
        val best = attempts.maxByOrNull { a -> a.metrics.firstOrNull { it.id == MetricId.JUMP_HEIGHT }?.value ?: -1.0 }
        val headline = best?.metrics?.map { it.copy(shotIndex = null) } ?: listOf(
            MetricId.JUMP_HEIGHT, MetricId.FLIGHT_TIME, MetricId.JUMP_HEIGHT_HIP, MetricId.COUNTERMOVEMENT_DEPTH, MetricId.TAKEOFF_VELOCITY
        ).map { Metric.notMeasured(it, "No jump detected.") }
        if (best != null && attempts.size > 1) warnings += "Best of ${attempts.size} attempts shown (attempt ${best.index + 1})."
        return SessionAnalyzer.finish(clip, SessionType.JUMP, view?.view, view?.confidence ?: 0.0, null, warnings, headline, attempts,
            headline, scaleNote = scale?.let { "Hip rise scaled from your entered height." }, jumpTest = test?.name)
    }

    fun attemptMetrics(
        idx: Int, clip: TrackedClip, j: JumpEvent, view: CameraView?, pxPerM: Double?, fpsFactor: Double, reachCm: Double?
    ): ShotResult {
        val f = clip.frames
        val m = ArrayList<Metric>()
        val ev = Evidence(j.lastGroundFrame, j.landingFrame, j.lastGroundFrame, OverlayHint(OverlayKind.FEET))
        val thrM = pxPerM?.let { j.thresholdPx / it }
        val tFlight = thrM?.let { JumpPhysics.correctedFlightSec(j.flightSec, it) } ?: j.flightSec
        val hFlightCm = 100 * JumpPhysics.heightFromFlight(tFlight)
        val accCm = 100 * JumpPhysics.heightUncertaintyM(tFlight, clip.fps)
        fun viewConf(id: MetricId) = when (view?.let { MetricValidity.of(id, it) }) {
            Validity.VALID, null -> 1.0; Validity.LOW_CONFIDENCE -> TrackingConfig.LOW_CONFIDENCE_VIEW_FACTOR; Validity.INVALID -> 0.0
        }

        // Landing check: knees/ankles bent much more at landing than at takeoff inflate flight time.
        val a = f[j.lastGroundFrame].pose; val b = f[j.landingFrame].pose
        val kneeDiff = if (a != null && b != null) PoseMath.kneeEither(a)?.first?.let { ka -> PoseMath.kneeEither(b)?.first?.let { kb -> abs(ka - kb) } } else null
        val ankleDiff = if (a != null && b != null) Side.entries.mapNotNull { s -> PoseMath.ankle(a, s)?.let { x -> PoseMath.ankle(b, s)?.let { y -> abs(x - y) } } }.maxOrNull() else null
        val landingAffected = (kneeDiff ?: 0.0) > TrackingConfig.LANDING_ANGLE_DIFF_DEG || (ankleDiff ?: 0.0) > TrackingConfig.LANDING_ANGLE_DIFF_DEG

        // Hip method.
        var hipCm: Double? = null
        if (pxPerM != null && a != null && a.allVisible(Lm.LEFT_HIP, Lm.RIGHT_HIP)) {
            val peak = (j.lastGroundFrame + 1 until j.landingFrame).mapNotNull { k ->
                f[k].pose?.takeIf { it.allVisible(Lm.LEFT_HIP, Lm.RIGHT_HIP) }?.hipMidPx()?.y
            }.minOrNull()
            if (peak != null) hipCm = 100 * (a.hipMidPx().y - peak) / pxPerM
        }
        val agree = hipCm?.let { abs(it - hFlightCm) / hFlightCm.coerceAtLeast(1.0) <= TrackingConfig.JUMP_METHOD_TOLERANCE }
        var conf = fpsFactor * viewConf(MetricId.FLIGHT_TIME)
        val notes = ArrayList<String>()
        notes += "Expected accuracy about ±${"%.1f".format(accCm)} cm at ${clip.fps.toInt()} fps (frame-rate limited, not lab grade)."
        when (agree) {
            true -> notes += "Hip-rise method agrees (${"%.1f".format(hipCm)} cm)."
            false -> { conf *= 0.6; notes += "Hip-rise method gives ${"%.1f".format(hipCm)} cm, more than 10% different. Pose noise or a tucked landing may explain it." }
            null -> conf *= 0.85
        }
        if (landingAffected) { conf *= 0.6; notes += "Landing form affected the result: knees or ankles were much more bent at landing than at takeoff, which lengthens flight time." }
        if (thrM == null) notes += "No height set, so the takeoff threshold could not be corrected (slight underestimate)."

        m += Metric.measured(MetricId.JUMP_HEIGHT, hFlightCm, conf, ev, notes.joinToString(" "), idx)
        m += Metric.measured(MetricId.FLIGHT_TIME, tFlight * 1000, conf, ev, shotIndex = idx)
        m += hipCm?.let { Metric.measured(MetricId.JUMP_HEIGHT_HIP, it, 0.6 * viewConf(MetricId.JUMP_HEIGHT_HIP), ev, shotIndex = idx) }
            ?: Metric.notMeasured(MetricId.JUMP_HEIGHT_HIP, if (pxPerM == null) "Needs your height (Settings) for scale." else "Hips not visible in flight.", idx)

        val cmStart = f.indexOfFirst { it.timeSec >= f[j.lastGroundFrame].timeSec - 1.5 }.coerceAtLeast(0)
        val cm = (cmStart..j.lastGroundFrame).mapNotNull { k -> f[k].pose?.let { PoseMath.kneeEither(it)?.let { kk -> k to kk.first } } }.minByOrNull { it.second }
        m += if (cm != null && viewConf(MetricId.COUNTERMOVEMENT_DEPTH) > 0)
            Metric.measured(MetricId.COUNTERMOVEMENT_DEPTH, cm.second, 0.8 * viewConf(MetricId.COUNTERMOVEMENT_DEPTH),
                Evidence(cmStart, j.lastGroundFrame, cm.first, OverlayHint(OverlayKind.JOINT_ANGLE, listOf(Lm.LEFT_HIP, Lm.LEFT_KNEE, Lm.LEFT_ANKLE))), shotIndex = idx)
        else Metric.notMeasured(MetricId.COUNTERMOVEMENT_DEPTH, "Knees not visible before takeoff.", idx)
        m += Metric.measured(MetricId.TAKEOFF_VELOCITY, JumpPhysics.takeoffVelocity(tFlight), conf, ev, shotIndex = idx)

        m += if (view != CameraView.FRONT) Metric.notMeasured(MetricId.TAKEOFF_SYMMETRY, "Needs a front view to see both feet.", idx)
        else if (j.leftToeOffSec == null || j.rightToeOffSec == null) Metric.notMeasured(MetricId.TAKEOFF_SYMMETRY, "One foot hidden at takeoff.", idx)
        else Metric.measured(MetricId.TAKEOFF_SYMMETRY, abs(j.leftToeOffSec - j.rightToeOffSec) * 1000, fpsFactor, ev, shotIndex = idx)

        if (reachCm != null) m += Metric.measured(MetricId.MAX_REACH, reachCm + hFlightCm, conf * 0.8, ev,
            "Estimate: your entered standing reach plus jump height.", idx)

        val scored = m.map { MetricScorer.score(it) }
        return ShotResult(idx, j.lastGroundFrame, j.lastGroundFrame, j.landingFrame, scored, overall = MetricScorer.overall(scored))
    }
}
