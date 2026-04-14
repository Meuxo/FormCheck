package com.meuxo.formcheck.domain.model

/** The five canonical phases of a basketball shot. */
enum class ShotPhase {
    SETUP,
    LOAD,
    LIFT,
    RELEASE,
    FOLLOW_THROUGH;

    fun displayName(): String = when (this) {
        SETUP -> "Setup"
        LOAD -> "Load"
        LIFT -> "Lift"
        RELEASE -> "Release"
        FOLLOW_THROUGH -> "Follow-Through"
    }
}
