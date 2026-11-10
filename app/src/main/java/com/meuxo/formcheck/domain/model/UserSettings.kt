package com.meuxo.formcheck.domain.model

import com.meuxo.formcheck.analysis.ShooterProfile
import com.meuxo.formcheck.tracking.ScaleCalibration
import com.meuxo.formcheck.tracking.Side

enum class Units { IMPERIAL, METRIC }

data class UserSettings(
    val onboarded: Boolean = false,
    val shootingHand: Side? = null,
    val heightCm: Double? = null,
    val units: Units = Units.IMPERIAL,
    val ballSize: Int = 7,
    val courtSize: CourtSize = CourtSize.HIGH_SCHOOL,
    val voiceCues: Boolean = true,
    val standingReachCm: Double? = null,
    val weeklyMakesGoal: Int = 100
) {
    fun profile() = ShooterProfile(shootingHand, heightCm?.div(100.0), ScaleCalibration.ballDiameterM(ballSize))
}
