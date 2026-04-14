package com.meuxo.formcheck.domain.usecase

import com.meuxo.formcheck.analysis.AngleEvaluator
import com.meuxo.formcheck.analysis.BalanceScorer
import com.meuxo.formcheck.analysis.FeedbackGenerator
import com.meuxo.formcheck.analysis.GuideHandDetector
import com.meuxo.formcheck.analysis.OverallScorer
import com.meuxo.formcheck.analysis.PoseEstimator
import com.meuxo.formcheck.analysis.ShotPhaseClassifier
import com.meuxo.formcheck.domain.model.AnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Pose -> phases -> evaluators -> scoring -> AnalysisResult. */
class AnalyzeShotUseCase @Inject constructor(
    private val poseEstimator: PoseEstimator,
    private val phaseClassifier: ShotPhaseClassifier,
    private val angleEvaluator: AngleEvaluator,
    private val balanceScorer: BalanceScorer,
    private val guideHandDetector: GuideHandDetector,
    private val overallScorer: OverallScorer,
    private val feedbackGenerator: FeedbackGenerator
) {
    suspend operator fun invoke(mediaUri: String): AnalysisResult =
        withContext(Dispatchers.Default) {
            val pose = poseEstimator.detectFromUri(mediaUri)
            val frames = pose.frames
            val phases = phaseClassifier.classify(frames)
            val angles = angleEvaluator.evaluate(frames, phases)
            val balance = balanceScorer.evaluate(frames, phases)
            val guide = guideHandDetector.evaluate(frames, phases)

            val overall = overallScorer.score(
                elbowFlare = angles.elbowFlareScore,
                elbowAngle = angles.elbowAngleScore,
                kneeBend = angles.kneeBendScore,
                setPoint = angles.setPointScore,
                balance = balance.score,
                guideHand = guide.score
            )

            val feedback = feedbackGenerator.build(angles, balance, guide)

            AnalysisResult(
                mediaUri = mediaUri,
                timestamp = System.currentTimeMillis(),
                overallScore = overall,
                elbowFlareScore = angles.elbowFlareScore,
                elbowAngleScore = angles.elbowAngleScore,
                kneeBendScore = angles.kneeBendScore,
                setPointScore = angles.setPointScore,
                balanceScore = balance.score,
                guideHandScore = guide.score,
                elbowAngleAtRelease = angles.elbowAngleDeg,
                kneeBendDepth = angles.kneeBendDeg,
                phaseFeedback = feedback,
                landmarksByFrame = frames,
                phasesByFrame = phases,
                sourceWidth = pose.width,
                sourceHeight = pose.height,
                isVideo = pose.isVideo,
                frameIntervalMs = pose.frameIntervalMs
            )
        }
}
