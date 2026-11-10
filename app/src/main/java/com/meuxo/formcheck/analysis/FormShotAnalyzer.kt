package com.meuxo.formcheck.analysis

import com.meuxo.formcheck.domain.model.ArcSummary
import com.meuxo.formcheck.domain.model.Evidence
import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.domain.model.OverlayHint
import com.meuxo.formcheck.domain.model.OverlayKind
import com.meuxo.formcheck.domain.model.ShotResult
import com.meuxo.formcheck.jump.JumpDetector
import com.meuxo.formcheck.jump.JumpEvent
import com.meuxo.formcheck.jump.JumpPhysics
import com.meuxo.formcheck.scoring.MetricScorer
import com.meuxo.formcheck.tracking.CameraView
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.MetricValidity
import com.meuxo.formcheck.tracking.Scale
import com.meuxo.formcheck.tracking.ScaleCalibration
import com.meuxo.formcheck.tracking.Side
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.tracking.TrackingConfig
import com.meuxo.formcheck.tracking.Validity
import com.meuxo.formcheck.tracking.ViewClassifier
import com.meuxo.formcheck.tracking.ViewEstimate
import com.meuxo.formcheck.tracking.geom.Angles
import com.meuxo.formcheck.tracking.geom.Stats
import kotlin.math.abs

data class ShooterProfile(
    /** Shooting hand from onboarding; null if the user never set it. */
    val hand: Side?,
    val heightM: Double?,
    val ballDiameterM: Double
)

data class FormAnalysis(
    val view: ViewEstimate?,
    val hand: Side,
    val warnings: List<String>,
    val shots: List<ShotResult>,
    val shooterScale: Scale?,
    val rimScale: Scale?
)

/**
 * Form-shot metrics (spec 3a–3e). Every metric is gated on: camera view (validity table),
 * landmark visibility, ball/rim availability and scale availability. Anything that cannot
 * be measured is returned as [Metric.notMeasured] with a one-line reason.
 */
class FormShotAnalyzer {

    fun analyze(clip: TrackedClip, profile: ShooterProfile): FormAnalysis {
        val frames = clip.frames
        val warnings = ArrayList<String>()
        val view = ViewClassifier.classify(frames)
        if (view == null) warnings += "No person was detected clearly enough to tell the camera angle."

        val detectedHand = HandednessDetector.detect(frames)
        val hand = profile.hand ?: detectedHand?.side ?: Side.RIGHT
        if (profile.hand == null) {
            warnings += if (detectedHand != null) "Shooting hand not set in your profile; detected ${detectedHand.side.label().lowercase()} hand."
            else "Shooting hand not set and could not be detected; assumed right. Set it in Settings."
        } else if (detectedHand != null && detectedHand.fromBall && detectedHand.side != profile.hand && detectedHand.confidence > 0.7) {
            warnings += "Your profile says ${profile.hand.label().lowercase()}-handed, but the ball sat on your " +
                "${detectedHand.side.label().lowercase()} hand in this clip. Metrics use the profile hand."
        }

        val all = frames.indices
        val shooterScale = ScaleCalibration.shooterPlane(frames, all, profile.ballDiameterM, profile.heightM)
        val rimScale = ScaleCalibration.rimPlane(clip.rim)
        if (shooterScale == null) warnings += "No real-world scale: enter your height in Settings so distances can be measured."

        val jumps = JumpDetector.detect(frames)
        val events = ShotEventDetector.detect(frames, hand)
        val shots = if (events.isEmpty()) {
            if (frames.count { it.ball != null } < 3) warnings += "The ball was not detected, so release-based metrics could not be measured."
            else warnings += "No release was found: the ball never clearly left the shooting hand above the shoulder."
            listOf(poseOnlyShot(clip, hand, view, shooterScale, jumps))
        } else events.mapIndexed { idx, ev ->
            shotMetrics(idx, clip, ev, hand, view, shooterScale, rimScale, jumps.firstOrNull { j ->
                j.lastGroundFrame <= ev.releaseFrame + 3 && j.landingFrame >= ev.releaseFrame - 3
            })
        }
        return FormAnalysis(view, hand, warnings, shots, shooterScale, rimScale)
    }

    // ------------------------------------------------------------------------------------

    private fun gate(id: MetricId, view: ViewEstimate?): Pair<Double, String?> {
        if (view == null) return 0.0 to "Camera angle unknown."
        return when (MetricValidity.of(id, view.view)) {
            Validity.VALID -> view.confidence to null
            Validity.LOW_CONFIDENCE -> view.confidence * TrackingConfig.LOW_CONFIDENCE_VIEW_FACTOR to null
            Validity.INVALID -> 0.0 to MetricValidity.reasonInvalid(id, view.view)
        }
    }

    private inline fun metric(
        id: MetricId, view: ViewEstimate?, shot: Int?, block: (viewConf: Double) -> Metric
    ): Metric {
        val (vc, reason) = gate(id, view)
        if (reason != null) return Metric.notMeasured(id, reason, shot)
        return MetricScorer.score(block(vc))
    }

    private fun hidden(id: MetricId, what: String, shot: Int?) =
        Metric.notMeasured(id, "$what not visible clearly enough (visibility below ${TrackingConfig.VISIBILITY_THRESHOLD}).", shot)

    fun shotMetrics(
        shotIndex: Int,
        clip: TrackedClip,
        ev: ShotEvents,
        hand: Side,
        view: ViewEstimate?,
        shooterScale: Scale?,
        rimScale: Scale?,
        jump: JumpEvent?
    ): ShotResult {
        val f = clip.frames
        val rel = ev.releaseFrame
        val relPose = f[rel].pose
        val guide = hand.other
        val m = ArrayList<Metric>()
        val noBallReason = "Ball not tracked after release."

        // Knee bend at deepest load: window from shot start to release.
        m += metric(MetricId.KNEE_BEND, view, shotIndex) { vc ->
            val best = (ev.startFrame..rel).mapNotNull { i -> f[i].pose?.let { p -> PoseMath.kneeEither(p)?.let { Triple(i, it.first, it.second) } } }
                .minByOrNull { it.second }
                ?: return@metric hidden(MetricId.KNEE_BEND, "Hips, knees or ankles", shotIndex)
            val p = f[best.first].pose!!
            val side = if (PoseMath.knee(p, hand) != null) hand else guide
            Metric.measured(MetricId.KNEE_BEND, best.second,
                vc * PoseMath.visConf(p, side.hip, side.knee, side.ankle) * (if (best.third == 2) 1.0 else 0.85),
                Evidence(ev.startFrame, rel, best.first, OverlayHint(OverlayKind.JOINT_ANGLE, listOf(side.hip, side.knee, side.ankle))),
                shotIndex = shotIndex)
        }

        // Elbow at set point.
        m += metric(MetricId.ELBOW_SET, view, shotIndex) { vc ->
            val s = ev.setFrame ?: return@metric Metric.notMeasured(MetricId.ELBOW_SET, "Set point not found: shooting arm hidden between dip and release.", shotIndex)
            val p = f[s].pose!!
            val a = PoseMath.elbow(p, hand) ?: return@metric hidden(MetricId.ELBOW_SET, "Shooting arm", shotIndex)
            Metric.measured(MetricId.ELBOW_SET, a, vc * PoseMath.visConf(p, hand.shoulder, hand.elbow, hand.wrist),
                Evidence(ev.dipFrame ?: s, rel, s, OverlayHint(OverlayKind.JOINT_ANGLE, listOf(hand.shoulder, hand.elbow, hand.wrist))),
                shotIndex = shotIndex)
        }

        // Elbow at release.
        m += metric(MetricId.ELBOW_RELEASE, view, shotIndex) { vc ->
            val p = relPose ?: return@metric hidden(MetricId.ELBOW_RELEASE, "Shooting arm", shotIndex)
            val a = PoseMath.elbow(p, hand) ?: return@metric hidden(MetricId.ELBOW_RELEASE, "Shooting arm", shotIndex)
            Metric.measured(MetricId.ELBOW_RELEASE, a, vc * PoseMath.visConf(p, hand.shoulder, hand.elbow, hand.wrist),
                Evidence(rel, rel, rel, OverlayHint(OverlayKind.JOINT_ANGLE, listOf(hand.shoulder, hand.elbow, hand.wrist))),
                shotIndex = shotIndex)
        }

        // Elbow alignment (front view): forearm tilt from vertical at the set point.
        m += metric(MetricId.ELBOW_FLARE, view, shotIndex) { vc ->
            val s = ev.setFrame ?: rel
            val p = f[s].pose ?: return@metric hidden(MetricId.ELBOW_FLARE, "Shooting arm", shotIndex)
            if (!p.allVisible(hand.elbow, hand.wrist)) return@metric hidden(MetricId.ELBOW_FLARE, "Shooting elbow or wrist", shotIndex)
            val tilt = Angles.angleFromVerticalUp(p.px(hand.elbow), p.px(hand.wrist))
            Metric.measured(MetricId.ELBOW_FLARE, tilt, vc * PoseMath.visConf(p, hand.elbow, hand.wrist),
                Evidence(s, s, s, OverlayHint(OverlayKind.LINE_FROM_VERTICAL, listOf(hand.elbow, hand.wrist))),
                shotIndex = shotIndex)
        }

        // Guide hand: sideways travel of guide wrist from 100 ms before to 150 ms after release.
        m += metric(MetricId.GUIDE_HAND, view, shotIndex) { vc ->
            val a = frameAtOffset(clip, rel, -0.10)
            val b = frameAtOffset(clip, rel, 0.15)
            val pa = f[a].pose; val pb = f[b].pose
            if (pa == null || pb == null || !pa.allVisible(guide.wrist, Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER) || !pb.visible(guide.wrist))
                return@metric hidden(MetricId.GUIDE_HAND, "Guide hand or shoulders", shotIndex)
            val shoulderW = pa.px(Lm.LEFT_SHOULDER).distanceTo(pa.px(Lm.RIGHT_SHOULDER))
            if (shoulderW < 5) return@metric Metric.notMeasured(MetricId.GUIDE_HAND, "Shoulders overlap in this view, so sideways travel cannot be scaled.", shotIndex)
            val dx = abs(pb.px(guide.wrist).x - pa.px(guide.wrist).x)
            Metric.measured(MetricId.GUIDE_HAND, 100.0 * dx / shoulderW, vc * minOf(pa.vis[guide.wrist], pb.vis[guide.wrist]).toDouble(),
                Evidence(a, b, rel, OverlayHint(OverlayKind.JOINT_ANGLE, listOf(guide.shoulder, guide.elbow, guide.wrist))),
                shotIndex = shotIndex)
        }

        // Trunk lean at release.
        m += metric(MetricId.TRUNK_LEAN, view, shotIndex) { vc ->
            val p = relPose ?: return@metric hidden(MetricId.TRUNK_LEAN, "Hips and shoulders", shotIndex)
            val lean = PoseMath.trunkLeanImage(p) ?: return@metric hidden(MetricId.TRUNK_LEAN, "Hips and shoulders", shotIndex)
            Metric.measured(MetricId.TRUNK_LEAN, lean, vc * PoseMath.visConf(p, Lm.LEFT_HIP, Lm.RIGHT_HIP, Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER),
                Evidence(rel, rel, rel, OverlayHint(OverlayKind.LINE_FROM_VERTICAL, listOf(Lm.LEFT_HIP, Lm.LEFT_SHOULDER), "Trunk")),
                shotIndex = shotIndex)
        }

        // Floor line from the feet before the dip (standing).
        val floorY = floorLine(clip, ev.startFrame, ev.dipFrame ?: rel)

        // Release height.
        m += metric(MetricId.RELEASE_HEIGHT, view, shotIndex) { vc ->
            val b = f[rel].ball ?: return@metric Metric.notMeasured(MetricId.RELEASE_HEIGHT, "Ball not detected at release.", shotIndex)
            val s = shooterScale ?: return@metric Metric.notMeasured(MetricId.RELEASE_HEIGHT, "No scale: ball size unclear and no height entered.", shotIndex)
            val fy = floorY ?: return@metric Metric.notMeasured(MetricId.RELEASE_HEIGHT, "Feet not visible before the shot, so the floor is unknown.", shotIndex)
            Metric.measured(MetricId.RELEASE_HEIGHT, (fy - b.y) / s.pxPerMeter, vc * s.confidence * b.confidence,
                Evidence(rel, rel, rel, OverlayHint(OverlayKind.BALL_POSITION)), note = "Scale from ${s.source.label}", shotIndex = shotIndex)
        }

        // Release angle, apex, entry angle from the arc.
        val arc = ev.arc
        val arcConf = arc?.let { ShotEventDetector.arcConfidence(it, ev.ballRadiusPx) } ?: 0.0
        val arcEvidence = Evidence(rel, ev.arcFrames.lastOrNull() ?: rel, rel, OverlayHint(OverlayKind.BALL_ARC))
        val arcTooNoisy = arc != null && arcConf < 0.15
        m += metric(MetricId.RELEASE_ANGLE, view, shotIndex) { vc ->
            if (arc == null) return@metric Metric.notMeasured(MetricId.RELEASE_ANGLE, noBallReason, shotIndex)
            if (arcTooNoisy) return@metric Metric.notMeasured(MetricId.RELEASE_ANGLE, "Ball path too noisy to fit an arc (residual ${"%.1f".format(arc.rmsResidualPx / ev.ballRadiusPx)} ball radii).", shotIndex)
            val v = arc.vel(f[rel].timeSec)
            Metric.measured(MetricId.RELEASE_ANGLE, Angles.elevationDeg(v.x, v.y), vc * arcConf, arcEvidence, shotIndex = shotIndex)
        }
        m += metric(MetricId.ARC_APEX, view, shotIndex) { vc ->
            if (arc == null) return@metric Metric.notMeasured(MetricId.ARC_APEX, noBallReason, shotIndex)
            if (arcTooNoisy) return@metric Metric.notMeasured(MetricId.ARC_APEX, "Ball path too noisy to fit an arc.", shotIndex)
            val fy = floorY ?: return@metric Metric.notMeasured(MetricId.ARC_APEX, "Feet not visible, so the floor is unknown.", shotIndex)
            val s = shooterScale ?: rimScale ?: return@metric Metric.notMeasured(MetricId.ARC_APEX, "No scale available.", shotIndex)
            val apex = arc.apex
            Metric.measured(MetricId.ARC_APEX, (fy - apex.y) / s.pxPerMeter, vc * arcConf * s.confidence * 0.8, arcEvidence,
                note = "Scale from ${s.source.label}; the apex is farther from the camera than the shooter, so this is approximate.",
                shotIndex = shotIndex)
        }
        m += metric(MetricId.ENTRY_ANGLE, view, shotIndex) { vc ->
            if (arc == null) return@metric Metric.notMeasured(MetricId.ENTRY_ANGLE, noBallReason, shotIndex)
            if (arcTooNoisy) return@metric Metric.notMeasured(MetricId.ENTRY_ANGLE, "Ball path too noisy to fit an arc.", shotIndex)
            val rim = clip.rim ?: return@metric Metric.notMeasured(MetricId.ENTRY_ANGLE, "Rim not located in the frame.", shotIndex)
            val t = arc.descendingTimeAtY(rim.y.toDouble())
                ?: return@metric Metric.notMeasured(MetricId.ENTRY_ANGLE, "Fitted ball path never reaches rim height.", shotIndex)
            val x = arc.pos(t).x
            if (abs(x - rim.x) > rim.width * 2.5) return@metric Metric.notMeasured(MetricId.ENTRY_ANGLE, "Ball reaches rim height far from the rim; the shot was not toward this rim.", shotIndex)
            val v = arc.vel(t)
            Metric.measured(MetricId.ENTRY_ANGLE, -Angles.elevationDeg(v.x, v.y), vc * arcConf * rim.confidence, arcEvidence, shotIndex = shotIndex)
        }

        // Shot timing: dip to release.
        m += metric(MetricId.SHOT_TIMING, view, shotIndex) { vc ->
            val d = ev.dipFrame ?: return@metric Metric.notMeasured(MetricId.SHOT_TIMING, "Dip not found: ball and shooting wrist hidden before release.", shotIndex)
            Metric.measured(MetricId.SHOT_TIMING, clip.msBetween(d, rel), vc * (if (ev.dipFromBall) 0.9 else 0.6),
                Evidence(d, rel, d, OverlayHint(OverlayKind.BALL_POSITION)),
                note = if (ev.dipFromBall) null else "Dip taken from the shooting wrist because the ball was hidden.",
                shotIndex = shotIndex)
        }

        // Follow-through hold.
        m += metric(MetricId.FOLLOW_THROUGH, view, shotIndex) { vc ->
            var k = rel
            var lastVisible = rel
            while (k < f.size) {
                val p = f[k].pose ?: break
                if (!p.allVisible(hand.wrist, hand.shoulder)) break
                val above = p.px(hand.wrist).y < p.px(hand.shoulder).y
                val ext = PoseMath.elbow(p, hand)?.let { it >= TrackingConfig.FOLLOW_THROUGH_MIN_ELBOW_DEG } ?: true
                if (!above || !ext) break
                lastVisible = k
                k++
            }
            if (lastVisible == rel && k == rel) return@metric hidden(MetricId.FOLLOW_THROUGH, "Shooting arm after release", shotIndex)
            val endedAtClipEnd = k >= f.size
            val trackLost = k < f.size && f[k].pose?.allVisible(hand.wrist, hand.shoulder) != true
            Metric.measured(MetricId.FOLLOW_THROUGH, clip.msBetween(rel, lastVisible), vc * (if (endedAtClipEnd || trackLost) 0.5 else 0.9),
                Evidence(rel, lastVisible, lastVisible, OverlayHint(OverlayKind.JOINT_ANGLE, listOf(hand.shoulder, hand.elbow, hand.wrist))),
                note = when {
                    endedAtClipEnd -> "Clip ended while you were still holding; the real hold is at least this long."
                    trackLost -> "Arm went out of view; the real hold may be longer."
                    else -> null
                }, shotIndex = shotIndex)
        }

        // Balance drift and landing drift.
        val endBalance = (jump?.landingFrame ?: ev.endFrame).coerceAtMost(f.size - 1)
        m += metric(MetricId.BALANCE_DRIFT, view, shotIndex) { vc ->
            val s = shooterScale ?: return@metric Metric.notMeasured(MetricId.BALANCE_DRIFT, "No scale: enter your height in Settings.", shotIndex)
            val offs = (ev.startFrame..endBalance).mapNotNull { i ->
                val p = f[i].pose ?: return@mapNotNull null
                if (!p.allVisible(Lm.LEFT_HIP, Lm.RIGHT_HIP, Lm.LEFT_ANKLE, Lm.RIGHT_ANKLE)) return@mapNotNull null
                i to (p.hipMidPx().x - p.ankleMidPx().x)
            }
            if (offs.size < 3) return@metric hidden(MetricId.BALANCE_DRIFT, "Hips and ankles", shotIndex)
            val base = offs.first().second
            val worst = offs.maxBy { abs(it.second - base) }
            Metric.measured(MetricId.BALANCE_DRIFT, 100.0 * abs(worst.second - base) / s.pxPerMeter,
                vc * s.confidence * offs.size / (endBalance - ev.startFrame + 1).toDouble(),
                Evidence(ev.startFrame, endBalance, worst.first, OverlayHint(OverlayKind.FEET)), shotIndex = shotIndex)
        }
        m += metric(MetricId.LANDING_DRIFT, view, shotIndex) { vc ->
            val s = shooterScale ?: return@metric Metric.notMeasured(MetricId.LANDING_DRIFT, "No scale: enter your height in Settings.", shotIndex)
            val j = jump ?: return@metric Metric.notMeasured(MetricId.LANDING_DRIFT, "No jump detected (set shot, or feet hidden).", shotIndex)
            val a = f[j.lastGroundFrame].pose; val b = f[j.landingFrame].pose
            if (a == null || b == null || !a.allVisible(Lm.LEFT_ANKLE, Lm.RIGHT_ANKLE) || !b.allVisible(Lm.LEFT_ANKLE, Lm.RIGHT_ANKLE))
                return@metric hidden(MetricId.LANDING_DRIFT, "Ankles", shotIndex)
            Metric.measured(MetricId.LANDING_DRIFT, 100.0 * a.ankleMidPx().distanceTo(b.ankleMidPx()) / s.pxPerMeter, vc * s.confidence,
                Evidence(j.lastGroundFrame, j.landingFrame, j.landingFrame, OverlayHint(OverlayKind.FEET)), shotIndex = shotIndex)
        }

        // Jump height on the jump shot (spec 7b: automatic on every jump shot).
        m += metric(MetricId.JUMP_HEIGHT, view, shotIndex) { vc ->
            val j = jump ?: return@metric Metric.notMeasured(MetricId.JUMP_HEIGHT, "No jump detected (set shot, or feet hidden).", shotIndex)
            val thrM = shooterScale?.let { j.thresholdPx / it.pxPerMeter }
            val t = thrM?.let { JumpPhysics.correctedFlightSec(j.flightSec, it) } ?: j.flightSec
            val fpsConf = (clip.fps / 60.0).coerceIn(0.3, 1.0)
            Metric.measured(MetricId.JUMP_HEIGHT, 100.0 * JumpPhysics.heightFromFlight(t), vc * fpsConf,
                Evidence(j.lastGroundFrame, j.landingFrame, j.lastGroundFrame, OverlayHint(OverlayKind.FEET)),
                note = "±${"%.1f".format(100 * JumpPhysics.heightUncertaintyM(t, clip.fps))} cm at ${clip.fps.toInt()} fps",
                shotIndex = shotIndex)
        }

        val arcSummary = arc?.let { ArcSummary.from(it, f[rel].timeSec, f[ev.arcFrames.lastOrNull() ?: rel].timeSec) }
        return ShotResult(
            index = shotIndex, startFrame = ev.startFrame, releaseFrame = rel, endFrame = ev.endFrame,
            metrics = m, arc = arcSummary, overall = MetricScorer.overall(m)
        )
    }

    /** Pose-only fallback when no release was found: everything release-based is Not measured. */
    private fun poseOnlyShot(clip: TrackedClip, hand: Side, view: ViewEstimate?, scale: Scale?, jumps: List<JumpEvent>): ShotResult {
        val f = clip.frames
        val reason = "Release frame unknown because the ball was not tracked leaving the hand."
        val m = ArrayList<Metric>()
        m += metric(MetricId.KNEE_BEND, view, 0) { vc ->
            val best = f.indices.mapNotNull { i -> f[i].pose?.let { p -> PoseMath.kneeEither(p)?.let { i to it.first } } }
                .minByOrNull { it.second } ?: return@metric hidden(MetricId.KNEE_BEND, "Hips, knees or ankles", 0)
            val p = f[best.first].pose!!
            val side = if (PoseMath.knee(p, hand) != null) hand else hand.other
            Metric.measured(MetricId.KNEE_BEND, best.second, vc * 0.7 * PoseMath.visConf(p, side.hip, side.knee, side.ankle),
                Evidence(0, f.size - 1, best.first, OverlayHint(OverlayKind.JOINT_ANGLE, listOf(side.hip, side.knee, side.ankle))),
                note = "Deepest bend in the whole clip, since the shot window is unknown.", shotIndex = 0)
        }
        listOf(MetricId.ELBOW_SET, MetricId.ELBOW_RELEASE, MetricId.ELBOW_FLARE, MetricId.GUIDE_HAND, MetricId.TRUNK_LEAN,
            MetricId.RELEASE_HEIGHT, MetricId.RELEASE_ANGLE, MetricId.ARC_APEX, MetricId.ENTRY_ANGLE,
            MetricId.SHOT_TIMING, MetricId.FOLLOW_THROUGH, MetricId.BALANCE_DRIFT).forEach { id ->
            val (_, viewReason) = gate(id, view)
            m += Metric.notMeasured(id, viewReason ?: reason, 0)
        }
        val j = jumps.firstOrNull()
        m += if (j != null && scale != null) {
            val t = JumpPhysics.correctedFlightSec(j.flightSec, j.thresholdPx / scale.pxPerMeter)
            Metric.measured(MetricId.JUMP_HEIGHT, 100 * JumpPhysics.heightFromFlight(t), (clip.fps / 60.0).coerceIn(0.3, 1.0),
                Evidence(j.lastGroundFrame, j.landingFrame, j.lastGroundFrame, OverlayHint(OverlayKind.FEET)), shotIndex = 0)
        } else Metric.notMeasured(MetricId.JUMP_HEIGHT, if (j == null) "No jump detected." else "No scale available.", 0)
        m += Metric.notMeasured(MetricId.LANDING_DRIFT, reason, 0)
        return ShotResult(0, 0, null, f.size - 1, m.map { MetricScorer.score(it) }, overall = MetricScorer.overall(m.map { MetricScorer.score(it) }))
    }

    private fun frameAtOffset(clip: TrackedClip, from: Int, offsetSec: Double): Int {
        val target = clip.frames[from].timeSec + offsetSec
        var best = from
        var bestD = Double.MAX_VALUE
        val range = if (offsetSec < 0) (0..from) else (from until clip.frames.size)
        for (i in range) {
            val d = abs(clip.frames[i].timeSec - target)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Floor y (px): median of the lowest visible heel/toe on standing frames in [a, b]. */
    private fun floorLine(clip: TrackedClip, a: Int, b: Int): Double? {
        val ys = (a..b).mapNotNull { i ->
            val p = clip.frames[i].pose ?: return@mapNotNull null
            listOf(Lm.LEFT_HEEL, Lm.RIGHT_HEEL, Lm.LEFT_FOOT_INDEX, Lm.RIGHT_FOOT_INDEX)
                .filter { p.visible(it) }.maxOfOrNull { p.px(it).y }
        }
        return if (ys.size < 2) null else Stats.median(ys)
    }
}
