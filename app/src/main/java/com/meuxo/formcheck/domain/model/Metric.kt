package com.meuxo.formcheck.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class SessionType(val label: String) {
    FORM("Form shot"),
    SHOOTAROUND("Shootaround"),
    DRIBBLE("Dribbling"),
    JUMP("Vertical jump")
}

@Serializable
enum class MetricUnit(val symbol: String) {
    DEGREES("°"),
    METERS("m"),
    CENTIMETERS("cm"),
    MILLISECONDS("ms"),
    PERCENT("%"),
    PER_SECOND("/s"),
    METERS_PER_SECOND("m/s"),
    COUNT("")
}

/**
 * Every metric the app can report. [description] is the help text shown in the UI and
 * says exactly what is measured.
 */
@Serializable
enum class MetricId(
    val displayName: String,
    val unit: MetricUnit,
    val description: String
) {
    // ---- Form shot ----
    KNEE_BEND("Knee bend at deepest load", MetricUnit.DEGREES,
        "Knee angle (hip-knee-ankle, 3D) at the lowest point of the dip. 180° is a straight leg."),
    ELBOW_SET("Elbow angle at set point", MetricUnit.DEGREES,
        "Shooting elbow angle (shoulder-elbow-wrist, 3D) at its most bent point between dip and release."),
    ELBOW_RELEASE("Elbow angle at release", MetricUnit.DEGREES,
        "Shooting elbow angle on the frame the ball leaves the hand."),
    ELBOW_FLARE("Elbow alignment", MetricUnit.DEGREES,
        "Front view only: how far the shooting forearm tilts from vertical at the set point."),
    GUIDE_HAND("Guide hand movement", MetricUnit.PERCENT,
        "Sideways travel of the guide wrist around release, as % of shoulder width."),
    TRUNK_LEAN("Trunk lean at release", MetricUnit.DEGREES,
        "Angle of the hip-to-shoulder line from vertical on the release frame."),
    RELEASE_HEIGHT("Release height", MetricUnit.METERS,
        "Height of the ball centre above the floor on the release frame."),
    RELEASE_ANGLE("Release angle", MetricUnit.DEGREES,
        "Launch angle of the ball above horizontal, from a parabola fitted to its flight."),
    ARC_APEX("Arc apex height", MetricUnit.METERS,
        "Highest point of the fitted ball flight above the floor."),
    ENTRY_ANGLE("Entry angle", MetricUnit.DEGREES,
        "Angle of the fitted ball flight below horizontal where it reaches rim height."),
    SHOT_TIMING("Dip to release", MetricUnit.MILLISECONDS,
        "Time from the lowest point of the ball (the dip) to release."),
    FOLLOW_THROUGH("Follow-through hold", MetricUnit.MILLISECONDS,
        "How long the shooting arm stays extended above the shoulder after release."),
    BALANCE_DRIFT("Balance drift", MetricUnit.CENTIMETERS,
        "Largest movement of the hips relative to the feet through the shot."),
    LANDING_DRIFT("Landing drift", MetricUnit.CENTIMETERS,
        "Distance between where the feet took off and where they landed."),
    JUMP_HEIGHT("Jump height", MetricUnit.CENTIMETERS,
        "Height from flight time: h = g·t²/8."),

    // ---- Consistency (multi-shot) ----
    CONSISTENCY_RELEASE_ANGLE("Release angle spread", MetricUnit.DEGREES,
        "Standard deviation of release angle across shots. Lower is more consistent."),
    CONSISTENCY_RELEASE_HEIGHT("Release height spread", MetricUnit.CENTIMETERS,
        "Standard deviation of release height across shots."),
    CONSISTENCY_TIMING("Timing spread", MetricUnit.MILLISECONDS,
        "Standard deviation of dip-to-release time across shots."),

    // ---- Shootaround ----
    FG_PERCENT("Field goal %", MetricUnit.PERCENT, "Makes divided by attempts with a known result."),
    MAKES("Makes", MetricUnit.COUNT, "Shots that passed down through the rim."),
    ATTEMPTS("Attempts", MetricUnit.COUNT, "Shots where the ball left the hands and rose above the head toward the rim."),
    LONGEST_STREAK("Longest make streak", MetricUnit.COUNT, "Most makes in a row."),

    // ---- Dribbling ----
    STANCE_LOWNESS("Stance height", MetricUnit.PERCENT,
        "Hip height as % of your standing hip height, averaged over the drill. Lower is a lower stance."),
    DRIBBLE_KNEE_ANGLE("Knee angle while dribbling", MetricUnit.DEGREES,
        "Average knee angle over the drill."),
    DRIBBLE_HEIGHT("Dribble height", MetricUnit.PERCENT,
        "Average ball peak height as % of your hip height. Knee height is about 50%."),
    BALL_PROTECTION("Ball protection (proxy)", MetricUnit.PERCENT,
        "Share of frames the ball is below the waist, outside the knee and the off arm is up. No defender is present, so this is a proxy."),
    FLUIDITY("Rhythm variation", MetricUnit.PERCENT,
        "Variation of time between bounces (coefficient of variation). Lower is steadier."),
    FUMBLES("Fumbles", MetricUnit.COUNT,
        "Times the ball left the control zone near your hands without a bounce."),
    EYES_UP("Eyes up (proxy)", MetricUnit.PERCENT,
        "Share of time the head stays near level. A proxy for not watching the ball."),
    PACE("Pace", MetricUnit.PER_SECOND, "Dribbles per second."),
    DRIBBLE_COUNT_LEFT("Left-hand dribbles", MetricUnit.COUNT, "Bounces controlled by the left hand."),
    DRIBBLE_COUNT_RIGHT("Right-hand dribbles", MetricUnit.COUNT, "Bounces controlled by the right hand."),
    DRILL_ACCURACY("Drill accuracy", MetricUnit.PERCENT,
        "Share of bounces that follow the drill's expected pattern."),

    // ---- Vertical jump ----
    FLIGHT_TIME("Flight time", MetricUnit.MILLISECONDS, "Time between takeoff and landing."),
    JUMP_HEIGHT_HIP("Jump height (hip method)", MetricUnit.CENTIMETERS,
        "Peak rise of the hips above their takeoff height, using your entered height for scale."),
    COUNTERMOVEMENT_DEPTH("Countermovement depth", MetricUnit.DEGREES,
        "Smallest knee angle before takeoff."),
    TAKEOFF_VELOCITY("Takeoff velocity", MetricUnit.METERS_PER_SECOND,
        "Vertical speed at takeoff from flight time: v = g·t/2."),
    TAKEOFF_SYMMETRY("Takeoff timing difference", MetricUnit.MILLISECONDS,
        "Front view: time between the left and right foot leaving the floor."),
    MAX_REACH("Estimated max reach", MetricUnit.CENTIMETERS,
        "Your entered standing reach plus jump height. An estimate.")
}

/** Where a measurement came from: frames to show and what to draw. */
@Serializable
data class Evidence(
    val frameStart: Int,
    val frameEnd: Int,
    /** The single frame to jump to when the user taps the metric. */
    val keyFrame: Int,
    val overlay: OverlayHint? = null
)

@Serializable
data class OverlayHint(
    val kind: OverlayKind,
    /** Landmark indices: 3 for an angle (a, vertex, b), 2 for a line. */
    val landmarks: List<Int> = emptyList(),
    val label: String = ""
)

@Serializable
enum class OverlayKind { JOINT_ANGLE, LINE_FROM_VERTICAL, BALL_POSITION, BALL_ARC, FEET }

/**
 * One reported metric. A metric is either measured ([value] non-null) or not measured
 * ([value] null and [notMeasuredReason] set). There is no third state.
 */
@Serializable
data class Metric(
    val id: MetricId,
    val value: Double?,
    val confidence: Float,
    val evidence: Evidence?,
    /** 0..100 when a target range exists for this metric and it was measured, else null. */
    val score: Int? = null,
    val notMeasuredReason: String? = null,
    /** Extra context shown under the value, e.g. "clip ended while still holding". */
    val note: String? = null,
    /** Which shot this belongs to; null for session-level metrics. */
    val shotIndex: Int? = null
) {
    val unit: MetricUnit get() = id.unit
    val isMeasured: Boolean get() = value != null

    companion object {
        fun notMeasured(id: MetricId, reason: String, shotIndex: Int? = null) =
            Metric(id, null, 0f, null, null, reason, shotIndex = shotIndex)

        fun measured(
            id: MetricId,
            value: Double,
            confidence: Double,
            evidence: Evidence?,
            note: String? = null,
            shotIndex: Int? = null
        ): Metric {
            require(!value.isNaN() && !value.isInfinite()) { "Measured metric $id must be finite" }
            return Metric(id, value, confidence.coerceIn(0.0, 1.0).toFloat(), evidence, note = note, shotIndex = shotIndex)
        }
    }
}
