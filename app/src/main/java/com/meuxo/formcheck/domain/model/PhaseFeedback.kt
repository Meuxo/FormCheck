package com.meuxo.formcheck.domain.model

/** Feedback bucket for one phase of the shot. */
data class PhaseFeedback(
    val phase: ShotPhase,
    val score: Int,
    val notes: List<String>
)
