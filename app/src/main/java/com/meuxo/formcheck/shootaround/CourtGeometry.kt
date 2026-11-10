package com.meuxo.formcheck.shootaround

import com.meuxo.formcheck.domain.model.CourtSize
import com.meuxo.formcheck.domain.model.CourtZone
import com.meuxo.formcheck.tracking.geom.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Half-court coordinates in FEET. Origin: centre of the baseline. +y points toward half
 * court. +x is the shooter's LEFT when facing the basket (the diagram is drawn with the
 * baseline at the top, so +x is the right side of the screen).
 *
 * Dimensions from the published rulebooks as commonly cited. Values marked APPROX are not
 * stated to the inch in a source available here and should be checked against the current
 * NFHS / NCAA rule book before relying on corner-zone boundaries.
 */
data class CourtSpec(
    val size: CourtSize,
    val laneWidth: Double,
    /** Arc radius from the centre of the basket. */
    val threeRadius: Double,
    /** Lateral distance of the corner three line from the centre line. */
    val cornerThree: Double,
    /** Distance from the baseline where the corner straight meets the arc. */
    val cornerStraightLength: Double
) {
    val hoop = Vec2(0.0, HOOP_FROM_BASELINE)
    val freeThrowLineY = FREE_THROW_FROM_BASELINE

    companion object {
        /** Rim centre is 63 in (5.25 ft) from the baseline at all three levels. */
        const val HOOP_FROM_BASELINE = 5.25
        /** Free-throw line is 15 ft from the backboard face, 4 ft from the baseline. */
        const val FREE_THROW_FROM_BASELINE = 19.0

        fun of(size: CourtSize) = when (size) {
            // NBA: 16 ft lane, 23 ft 9 in arc, 22 ft corners, corner straight 14 ft from baseline.
            CourtSize.NBA -> CourtSpec(size, 16.0, 23.75, 22.0, 14.0)
            // NCAA (men, 2019+): 12 ft lane, 22 ft 1.75 in arc, 21 ft 8 in corners. Corner straight APPROX.
            CourtSize.COLLEGE -> CourtSpec(size, 12.0, 22.146, 21.65, 9.86)
            // NFHS: 12 ft lane, 19 ft 9 in arc; straight lines from the baseline for 63 in. APPROX.
            CourtSize.HIGH_SCHOOL -> CourtSpec(size, 12.0, 19.75, 19.75, 5.25)
        }
    }
}

/** Points the user can tap during calibration (spec 6). */
enum class CourtPoint(val label: String) {
    PAINT_BASELINE_LEFT("Paint corner on the baseline, shooter's left"),
    PAINT_BASELINE_RIGHT("Paint corner on the baseline, shooter's right"),
    FREE_THROW_LEFT("Free-throw line, left end"),
    FREE_THROW_RIGHT("Free-throw line, right end"),
    CORNER_THREE_LEFT("Three-point line on the baseline, left"),
    CORNER_THREE_RIGHT("Three-point line on the baseline, right"),
    TOP_OF_ARC("Top of the three-point arc");

    fun position(c: CourtSpec): Vec2 = when (this) {
        PAINT_BASELINE_LEFT -> Vec2(c.laneWidth / 2, 0.0)
        PAINT_BASELINE_RIGHT -> Vec2(-c.laneWidth / 2, 0.0)
        FREE_THROW_LEFT -> Vec2(c.laneWidth / 2, c.freeThrowLineY)
        FREE_THROW_RIGHT -> Vec2(-c.laneWidth / 2, c.freeThrowLineY)
        CORNER_THREE_LEFT -> Vec2(c.cornerThree, 0.0)
        CORNER_THREE_RIGHT -> Vec2(-c.cornerThree, 0.0)
        TOP_OF_ARC -> Vec2(0.0, c.hoop.y + c.threeRadius)
    }
}

object Zones {
    /** Degrees either side of straight-on that count as "center" / "top of key". HEURISTIC split. */
    const val CENTER_HALF_ANGLE_MID = 30.0
    const val CENTER_HALF_ANGLE_THREE = 22.5

    fun isThree(p: Vec2, c: CourtSpec): Boolean {
        val d = hypot(p.x - c.hoop.x, p.y - c.hoop.y)
        return if (p.y <= c.cornerStraightLength) abs(p.x) >= c.cornerThree else d >= c.threeRadius
    }

    fun classify(p: Vec2, c: CourtSpec): CourtZone {
        // Angle from straight-on, positive to the shooter's left (+x).
        val ang = Math.toDegrees(atan2(p.x - c.hoop.x, p.y - c.hoop.y))
        val left = p.x > 0
        if (isThree(p, c)) {
            if (p.y <= c.cornerStraightLength) return if (left) CourtZone.CORNER_THREE_LEFT else CourtZone.CORNER_THREE_RIGHT
            return when {
                abs(ang) <= CENTER_HALF_ANGLE_THREE -> CourtZone.TOP_OF_KEY_THREE
                left -> CourtZone.WING_THREE_LEFT
                else -> CourtZone.WING_THREE_RIGHT
            }
        }
        if (abs(p.x) <= c.laneWidth / 2 && p.y <= c.freeThrowLineY) return CourtZone.PAINT
        return when {
            abs(ang) <= CENTER_HALF_ANGLE_MID -> CourtZone.MID_CENTER
            left -> CourtZone.MID_LEFT
            else -> CourtZone.MID_RIGHT
        }
    }
}
