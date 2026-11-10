package com.meuxo.formcheck.domain.model

import com.meuxo.formcheck.scoring.OverallScore
import com.meuxo.formcheck.tracking.CameraView
import com.meuxo.formcheck.tracking.ProjectileFit
import com.meuxo.formcheck.tracking.Side
import kotlinx.serialization.Serializable

/** Fitted ball flight, kept so playback can draw the arc. Times are clip seconds. */
@Serializable
data class ArcSummary(
    val t0: Double, val x0: Double, val vx: Double,
    val y0: Double, val vy: Double, val ay: Double,
    val startSec: Double, val endSec: Double,
    val rmsResidualPx: Double
) {
    fun toFit() = ProjectileFit(t0, x0, vx, y0, vy, ay, rmsResidualPx, 0, 0)

    companion object {
        fun from(f: ProjectileFit, startSec: Double, endSec: Double) =
            ArcSummary(f.t0, f.x0, f.vx, f.y0, f.vy, f.ay, startSec, endSec, f.rmsResidualPx)
    }
}

@Serializable
enum class ShotOutcome(val label: String) { MAKE("Make"), MISS("Miss"), UNCERTAIN("Uncertain") }

@Serializable
enum class CourtZone(val label: String) {
    PAINT("Paint"),
    MID_LEFT("Midrange left"),
    MID_CENTER("Midrange center"),
    MID_RIGHT("Midrange right"),
    CORNER_THREE_LEFT("Left corner three"),
    CORNER_THREE_RIGHT("Right corner three"),
    WING_THREE_LEFT("Left wing three"),
    WING_THREE_RIGHT("Right wing three"),
    TOP_OF_KEY_THREE("Top of key three")
}

@Serializable
data class ShotResult(
    val index: Int,
    val startFrame: Int,
    val releaseFrame: Int?,
    val endFrame: Int,
    val metrics: List<Metric>,
    val arc: ArcSummary? = null,
    val outcome: ShotOutcome? = null,
    val outcomeCorrected: Boolean = false,
    val outcomeReason: String? = null,
    /** Court coordinates in feet (origin: centre of baseline, +y toward half court). */
    val courtX: Double? = null,
    val courtY: Double? = null,
    val zone: CourtZone? = null,
    val locationReason: String? = null,
    val overall: OverallScore? = null
)

@Serializable
data class SessionResult(
    val type: SessionType,
    val analyzedAtMs: Long,
    val fps: Double,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val view: CameraView?,
    val viewConfidence: Double,
    val shootingHand: Side?,
    /** Plain-language notes about things that limited the analysis. */
    val warnings: List<String>,
    /** Session-level metrics (consistency, makes, drill metrics, jump results…). */
    val metrics: List<Metric>,
    val shots: List<ShotResult>,
    val overall: OverallScore,
    val biggestFix: Metric?,
    val biggestFixTip: String?,
    val scaleNote: String? = null,
    val drillId: String? = null,
    val jumpTest: String? = null
)
