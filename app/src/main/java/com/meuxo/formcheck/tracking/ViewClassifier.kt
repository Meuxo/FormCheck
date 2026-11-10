package com.meuxo.formcheck.tracking

import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.tracking.geom.Stats
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
enum class CameraView(val label: String) { SIDE("Side"), FRONT("Front"), ANGLED("Angled") }

data class ViewEstimate(
    val view: CameraView,
    /** Share of usable frames that agree with [view]. */
    val confidence: Double,
    val shoulderToTorsoRatio: Double,
    val depthRatio: Double,
    val framesUsed: Int
)

/**
 * Classifies the clip as side, front or angled from two cues (spec 3b):
 *  1. image shoulder width / torso height (shoulders overlap in a side view), and
 *  2. world-landmark depth difference between the shoulders relative to their 3D width.
 */
object ViewClassifier {

    fun classifyFrame(p: PoseFrame): Triple<CameraView, Double, Double>? {
        if (!p.allVisible(Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, Lm.LEFT_HIP, Lm.RIGHT_HIP)) return null
        val shoulderW = p.px(Lm.LEFT_SHOULDER).distanceTo(p.px(Lm.RIGHT_SHOULDER))
        val torsoH = p.shoulderMidPx().distanceTo(p.hipMidPx())
        if (torsoH < 1e-6) return null
        val ratio = shoulderW / torsoH
        val ls = p.w(Lm.LEFT_SHOULDER)
        val rs = p.w(Lm.RIGHT_SHOULDER)
        val width3d = (ls - rs).length()
        val depthRatio = if (width3d < 1e-6) 0.0 else abs(ls.z - rs.z) / width3d
        val view = when {
            ratio <= TrackingConfig.VIEW_SIDE_MAX_RATIO || depthRatio >= TrackingConfig.VIEW_SIDE_MIN_DEPTH_RATIO -> CameraView.SIDE
            ratio >= TrackingConfig.VIEW_FRONT_MIN_RATIO && depthRatio <= TrackingConfig.VIEW_FRONT_MAX_DEPTH_RATIO -> CameraView.FRONT
            else -> CameraView.ANGLED
        }
        return Triple(view, ratio, depthRatio)
    }

    /** Majority vote over frames (optionally restricted to [range]). Null if no usable frame. */
    fun classify(frames: List<TrackedFrame>, range: IntRange = frames.indices): ViewEstimate? {
        val per = range.mapNotNull { i -> frames.getOrNull(i)?.pose?.let { classifyFrame(it) } }
        if (per.isEmpty()) return null
        val counts = per.groupingBy { it.first }.eachCount()
        val best = counts.maxBy { it.value }
        return ViewEstimate(
            view = best.key,
            confidence = best.value.toDouble() / per.size,
            shoulderToTorsoRatio = Stats.median(per.map { it.second }),
            depthRatio = Stats.median(per.map { it.third }),
            framesUsed = per.size
        )
    }
}

enum class Validity { VALID, LOW_CONFIDENCE, INVALID }

/** The metric/view validity table from spec section 3b. */
object MetricValidity {
    private val V = Validity.VALID
    private val L = Validity.LOW_CONFIDENCE
    private val X = Validity.INVALID

    // Order: SIDE, FRONT, ANGLED
    private val table: Map<MetricId, Triple<Validity, Validity, Validity>> = mapOf(
        MetricId.KNEE_BEND to Triple(V, X, L),
        MetricId.ELBOW_SET to Triple(V, X, L),
        MetricId.ELBOW_RELEASE to Triple(V, X, L),
        MetricId.ELBOW_FLARE to Triple(X, V, X),
        MetricId.GUIDE_HAND to Triple(L, V, L),
        MetricId.TRUNK_LEAN to Triple(V, V, L),
        MetricId.RELEASE_HEIGHT to Triple(V, V, V),
        MetricId.RELEASE_ANGLE to Triple(V, X, X),
        MetricId.ARC_APEX to Triple(V, X, X),
        MetricId.ENTRY_ANGLE to Triple(V, X, X),
        MetricId.BALANCE_DRIFT to Triple(V, V, V),
        MetricId.LANDING_DRIFT to Triple(V, V, V),
        // Not in the spec table: timing metrics do not depend on view.
        MetricId.SHOT_TIMING to Triple(V, V, V),
        MetricId.FOLLOW_THROUGH to Triple(V, V, V),
        MetricId.JUMP_HEIGHT to Triple(V, V, V),
        // Jump test (spec 7b: side or front). Symmetry needs both feet side by side: front only.
        MetricId.FLIGHT_TIME to Triple(V, V, L),
        MetricId.JUMP_HEIGHT_HIP to Triple(V, V, L),
        MetricId.COUNTERMOVEMENT_DEPTH to Triple(V, L, L),
        MetricId.TAKEOFF_VELOCITY to Triple(V, V, L),
        MetricId.TAKEOFF_SYMMETRY to Triple(X, V, X)
    )

    fun of(id: MetricId, view: CameraView): Validity {
        val row = table[id] ?: return V
        return when (view) {
            CameraView.SIDE -> row.first
            CameraView.FRONT -> row.second
            CameraView.ANGLED -> row.third
        }
    }

    fun reasonInvalid(id: MetricId, view: CameraView): String {
        val good = CameraView.entries.filter { of(id, it) == V }.joinToString(" or ") { it.label.lowercase() }
        return "Needs a $good view; this clip was filmed from the ${view.label.lowercase()}."
    }
}
