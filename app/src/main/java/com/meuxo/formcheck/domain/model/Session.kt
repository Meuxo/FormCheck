package com.meuxo.formcheck.domain.model

/** Domain-level session record (mirrors SessionEntity). */
data class Session(
    val id: Long,
    val timestamp: Long,
    val mediaUri: String,
    val overallScore: Int,
    val elbowFlareScore: Int,
    val elbowAngleAtRelease: Float,
    val kneeBendDepth: Float,
    val balanceScore: Int,
    val guideHandScore: Int,
    val phaseFeedback: List<PhaseFeedback>
)
