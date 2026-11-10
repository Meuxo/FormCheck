package com.meuxo.formcheck.analysis

import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.SessionParams
import com.meuxo.formcheck.domain.model.SessionResult
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.domain.model.UserSettings
import com.meuxo.formcheck.scoring.ConsistencyScorer
import com.meuxo.formcheck.scoring.FixTips
import com.meuxo.formcheck.scoring.MetricScorer
import com.meuxo.formcheck.scoring.OverallScore
import com.meuxo.formcheck.tracking.ScaleCalibration
import com.meuxo.formcheck.tracking.TrackedClip
import javax.inject.Inject

/** Turns a tracked clip into a [SessionResult] for the session's mode. */
class SessionAnalyzer @Inject constructor() {

    fun analyze(clip: TrackedClip, params: SessionParams, settings: UserSettings): SessionResult {
        val profile = ShooterProfile(
            hand = params.shootingHand ?: settings.shootingHand,
            heightM = params.heightM ?: settings.heightCm?.div(100.0),
            ballDiameterM = ScaleCalibration.ballDiameterM(params.ballSize)
        )
        return when (params.type) {
            SessionType.FORM -> form(clip, profile)
            SessionType.SHOOTAROUND -> com.meuxo.formcheck.shootaround.ShootaroundAnalyzer.analyze(clip, profile, params.court)
            SessionType.DRIBBLE -> com.meuxo.formcheck.dribble.DribbleAnalyzer.analyze(clip, com.meuxo.formcheck.dribble.DrillLibrary.byId(params.drillId))
            SessionType.JUMP -> com.meuxo.formcheck.jump.JumpAnalyzer.analyze(clip, params.jumpTest, profile.heightM,
                params.standingReachCm ?: settings.standingReachCm)
        }
    }

    fun form(clip: TrackedClip, profile: ShooterProfile): SessionResult {
        val a = FormShotAnalyzer().analyze(clip, profile)
        val consistency = if (a.shots.size >= ConsistencyScorer.MIN_SHOTS) ConsistencyScorer.compute(a.shots.map { it.metrics }) else emptyList()
        // Headline: the first shot's metrics plus any consistency metrics.
        val headline = (a.shots.firstOrNull()?.metrics ?: emptyList()) + consistency
        return finish(clip, SessionType.FORM, a.view?.view, a.view?.confidence ?: 0.0, a.hand, a.warnings, consistency, a.shots, headline,
            scaleNote = a.shooterScale?.let { "Distances scaled from ${it.source.label}." })
    }

    companion object {
        fun finish(
            clip: TrackedClip, type: SessionType, view: com.meuxo.formcheck.tracking.CameraView?, viewConf: Double,
            hand: com.meuxo.formcheck.tracking.Side?, warnings: List<String>, sessionMetrics: List<Metric>,
            shots: List<com.meuxo.formcheck.domain.model.ShotResult>, headline: List<Metric>,
            scaleNote: String? = null, drillId: String? = null, jumpTest: String? = null
        ): SessionResult {
            val overall = MetricScorer.overall(headline)
            val fix = MetricScorer.biggestFix(headline)
            val fpsWarning = if (clip.fps in 1.0..45.0 && type != SessionType.DRIBBLE)
                listOf("Video is ${clip.fps.toInt()} fps. Fast events (release, takeoff) are timed to ±${(1000 / clip.fps).toInt()} ms; 60 fps or more is recommended.")
            else emptyList()
            return SessionResult(
                type = type, analyzedAtMs = System.currentTimeMillis(), fps = clip.fps, width = clip.width,
                height = clip.height, frameCount = clip.frames.size, view = view, viewConfidence = viewConf,
                shootingHand = hand, warnings = warnings + fpsWarning, metrics = sessionMetrics, shots = shots,
                overall = overall, biggestFix = fix, biggestFixTip = fix?.let { FixTips.tip(it) },
                scaleNote = scaleNote, drillId = drillId, jumpTest = jumpTest
            )
        }
    }
}
