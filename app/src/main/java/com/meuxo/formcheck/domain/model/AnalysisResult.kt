package com.meuxo.formcheck.domain.model

/** Bundles every signal produced by AnalyzeShotUseCase for one shot. */
data class AnalysisResult(
    val mediaUri: String,
    val timestamp: Long,
    val overallScore: Int,
    val elbowFlareScore: Int,
    val elbowAngleScore: Int,
    val kneeBendScore: Int,
    val setPointScore: Int,
    val balanceScore: Int,
    val guideHandScore: Int,
    val elbowAngleAtRelease: Float,
    val kneeBendDepth: Float,
    val phaseFeedback: List<PhaseFeedback>,
    val landmarksByFrame: List<PoseLandmarks>,
    val phasesByFrame: List<ShotPhase>,
    /** Source media pixel dimensions (post-rotation), used for overlay scaling. */
    val sourceWidth: Int,
    val sourceHeight: Int,
    val isVideo: Boolean,
    /** Frame sample interval in milliseconds (10 fps = 100ms). */
    val frameIntervalMs: Long
)
