package com.meuxo.formcheck.domain.model

import com.meuxo.formcheck.tracking.Side
import com.meuxo.formcheck.tracking.geom.Vec2
import kotlinx.serialization.Serializable

enum class SessionStatus { IMPORTING, PENDING, ANALYZING, DONE, FAILED, LEGACY }

/** Library row. */
data class Session(
    val id: Long,
    val timestamp: Long,
    val type: SessionType,
    val title: String?,
    val status: SessionStatus,
    val statusMessage: String?,
    val mediaPath: String?,
    val thumbPath: String?,
    val resultPath: String?,
    val trackPath: String?,
    val overallScore: Int?,
    val includedMetrics: Int?,
    val totalMetrics: Int?,
    val makes: Int?,
    val attempts: Int?,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val legacyMediaUri: String?
) {
    val displayTitle: String get() = title ?: type.label
}

@Serializable
data class PointNorm(val x: Double, val y: Double) {
    fun toPx(w: Int, h: Int) = Vec2(x * w, y * h)
}

/** One court calibration tap: image point (normalised) matched to a known court point. */
@Serializable
data class CourtTap(val image: PointNorm, val courtPointId: String)

@Serializable
data class CourtCalibration(
    val courtSize: CourtSize,
    val taps: List<CourtTap>,
    /** Frame index from which this calibration applies (recalibration after the phone moved). */
    val fromFrame: Int = 0
)

@Serializable
enum class CourtSize(val label: String) { HIGH_SCHOOL("High school"), COLLEGE("College"), NBA("NBA") }

@Serializable
enum class JumpTest(val label: String, val description: String) {
    COUNTERMOVEMENT("Standing vertical", "Stand still, dip and jump straight up. Arms free."),
    NO_STEP("No-step", "Feet planted, jump without a dip or a step."),
    APPROACH("Max approach", "Take your normal run-up and jump as high as you can.")
}

/** Inputs chosen before analysis, saved next to the media as params.json. */
@Serializable
data class SessionParams(
    val type: SessionType,
    val shootingHand: Side? = null,
    val heightM: Double? = null,
    val ballSize: Int = 7,
    val shooterSeed: PointNorm? = null,
    /** Rim from two taps on the left and right edge of the rim (normalised). */
    val rimLeft: PointNorm? = null,
    val rimRight: PointNorm? = null,
    val court: List<CourtCalibration> = emptyList(),
    val drillId: String? = null,
    val jumpTest: JumpTest? = null,
    val standingReachCm: Double? = null,
    /** True when recorded live; the live pass is only a preview. */
    val recordedLive: Boolean = false
)
