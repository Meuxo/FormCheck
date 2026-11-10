package com.meuxo.formcheck.scoring

import com.meuxo.formcheck.domain.model.MetricId

/** What kind of evidence backs a target range. Shown to the user in help text. */
enum class SourceKind(val label: String) {
    PHYSICS("Physics"),
    PUBLISHED_DATA("Published data"),
    COACHING_CONVENTION("Coaching convention, not from a study"),
    HEURISTIC("App heuristic, not from a study")
}

/**
 * A target range. Inside [low, high] scores 100%. Outside, the score drops linearly and
 * reaches 0% at [falloff] units beyond the nearest edge.
 */
data class TargetRange(
    val low: Double,
    val high: Double,
    val falloff: Double,
    val kind: SourceKind,
    val source: String
) {
    init { require(low <= high && falloff > 0) }
}

/**
 * The single home for every target range (spec 3f).
 *
 * Rules followed here:
 *  - Ranges are deliberately wide. Only one (release angle) comes from physics; one (entry
 *    angle) comes from a commercial data provider; the rest are coaching convention or app
 *    heuristics and say so. None is presented as a precise ideal.
 *  - Metrics with no defensible ideal (release height, arc apex, timing, jump height, pace…)
 *    have no entry. They are shown with their value but no percentage and are excluded from
 *    the overall score. Their spread across shots feeds the consistency score instead.
 */
object ScoringConfig {

    val targets: Map<MetricId, TargetRange> = mapOf(
        MetricId.RELEASE_ANGLE to TargetRange(
            46.0, 55.0, 12.0, SourceKind.PHYSICS,
            "Brancazio (1981), \"Physics of basketball\", Am. J. Phys. 49:356. The launch angle " +
                "needing the least speed is about 45° plus half the elevation angle from release " +
                "point to rim, which gives roughly 48–53° for free throws and threes. Minimum " +
                "speed is not the same as maximum accuracy, so the range is widened."
        ),
        MetricId.ENTRY_ANGLE to TargetRange(
            42.0, 48.0, 12.0, SourceKind.PUBLISHED_DATA,
            "Noah Basketball's published guidance from its shot-tracking data recommends about " +
                "45° entry. Proprietary dataset, not peer reviewed."
        ),
        MetricId.ELBOW_RELEASE to TargetRange(
            160.0, 180.0, 30.0, SourceKind.COACHING_CONVENTION,
            "Coaches teach a near-full extension of the shooting arm at release."
        ),
        MetricId.ELBOW_SET to TargetRange(
            70.0, 100.0, 30.0, SourceKind.COACHING_CONVENTION,
            "The 'L' or roughly right-angle elbow at the set point taught in most form-shooting " +
                "progressions. Shooters vary a lot here."
        ),
        MetricId.KNEE_BEND to TargetRange(
            100.0, 145.0, 30.0, SourceKind.COACHING_CONVENTION,
            "A visible but not deep knee bend at the bottom of the dip. Convention, not a study."
        ),
        MetricId.ELBOW_FLARE to TargetRange(
            0.0, 10.0, 25.0, SourceKind.COACHING_CONVENTION,
            "Elbow under the ball: forearm close to vertical at the set point, seen from the front."
        ),
        MetricId.GUIDE_HAND to TargetRange(
            0.0, 15.0, 45.0, SourceKind.HEURISTIC,
            "A passive guide hand should not travel sideways much at release. The 15% of shoulder " +
                "width allowance covers pose noise."
        ),
        MetricId.TRUNK_LEAN to TargetRange(
            0.0, 10.0, 20.0, SourceKind.COACHING_CONVENTION,
            "Shoot 'straight up and down' with little lean at release."
        ),
        MetricId.FOLLOW_THROUGH to TargetRange(
            500.0, 10_000.0, 500.0, SourceKind.COACHING_CONVENTION,
            "Hold the follow-through until the ball reaches the rim; about half a second or more."
        ),
        MetricId.BALANCE_DRIFT to TargetRange(
            0.0, 8.0, 20.0, SourceKind.HEURISTIC,
            "Hips staying over the feet. 8 cm allows for normal sway and pose noise."
        ),
        MetricId.LANDING_DRIFT to TargetRange(
            0.0, 20.0, 40.0, SourceKind.COACHING_CONVENTION,
            "Land close to where you took off. A small forward drift is normal on jump shots."
        ),

        // ---- Consistency: spread across shots, lower is better ----
        MetricId.CONSISTENCY_RELEASE_ANGLE to TargetRange(
            0.0, 2.0, 6.0, SourceKind.HEURISTIC,
            "Spread of release angle. Needs no ideal value; the 0–2° full-score band is an app " +
                "heuristic for mapping the spread to a percentage."
        ),
        MetricId.CONSISTENCY_RELEASE_HEIGHT to TargetRange(
            0.0, 3.0, 10.0, SourceKind.HEURISTIC,
            "Spread of release height. Mapping to a percentage is an app heuristic."
        ),
        MetricId.CONSISTENCY_TIMING to TargetRange(
            0.0, 40.0, 120.0, SourceKind.HEURISTIC,
            "Spread of dip-to-release time. Mapping to a percentage is an app heuristic."
        ),

        // ---- Dribbling ----
        MetricId.STANCE_LOWNESS to TargetRange(
            65.0, 85.0, 20.0, SourceKind.COACHING_CONVENTION,
            "Athletic stance: hips clearly below standing height. Convention, not a study."
        ),
        MetricId.BALL_PROTECTION to TargetRange(
            70.0, 100.0, 50.0, SourceKind.HEURISTIC,
            "Proxy with no defender present: ball low, outside the knee, off arm up."
        ),
        MetricId.FLUIDITY to TargetRange(
            0.0, 15.0, 35.0, SourceKind.HEURISTIC,
            "Variation in time between bounces. 15% full-score band is an app heuristic."
        ),
        MetricId.FUMBLES to TargetRange(
            0.0, 0.0, 5.0, SourceKind.HEURISTIC,
            "Each fumble costs 20%."
        ),
        MetricId.EYES_UP to TargetRange(
            80.0, 100.0, 50.0, SourceKind.HEURISTIC,
            "Proxy from head pitch; looking down at the ball lowers it."
        ),
        MetricId.DRILL_ACCURACY to TargetRange(
            85.0, 100.0, 45.0, SourceKind.HEURISTIC,
            "Share of bounces matching the drill pattern."
        ),

        // ---- Jump ----
        MetricId.TAKEOFF_SYMMETRY to TargetRange(
            0.0, 30.0, 70.0, SourceKind.HEURISTIC,
            "Both feet should leave together on a two-foot jump; 30 ms is about one frame at 30 fps."
        )
    )

    /** Dribble height targets per drill (ball apex as % of hip height). */
    val dribbleHeightTargets: Map<String, TargetRange> = mapOf(
        "low" to TargetRange(0.0, 55.0, 30.0, SourceKind.COACHING_CONVENTION,
            "Low dribble: at or below the knee (knee ≈ 50% of hip height)."),
        "waist" to TargetRange(0.0, 100.0, 30.0, SourceKind.COACHING_CONVENTION,
            "Control dribble: no higher than the waist."),
        "high" to TargetRange(80.0, 130.0, 30.0, SourceKind.COACHING_CONVENTION,
            "Power/high dribble: around waist height.")
    )

    /** Score for [value] against [range]: 100 inside, linear to 0 at the falloff distance. */
    fun score(value: Double, range: TargetRange): Int {
        val d = when {
            value < range.low -> range.low - value
            value > range.high -> value - range.high
            else -> 0.0
        }
        return (100.0 * (1.0 - d / range.falloff)).coerceIn(0.0, 100.0).let { Math.round(it).toInt() }
    }
}
