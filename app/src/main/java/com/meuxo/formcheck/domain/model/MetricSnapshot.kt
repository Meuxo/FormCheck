package com.meuxo.formcheck.domain.model

/** A single point on the progress chart. */
data class MetricSnapshot(
    val timestamp: Long,
    val overallScore: Int,
    val elbowAngleAtRelease: Float,
    val kneeBendDepth: Float,
    val balanceScore: Int
)
