package com.meuxo.formcheck.util

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.atan2

object AngleUtils {

    /**
     * Returns the interior angle (in degrees) at [vertex] formed by
     * the segments vertex->a and vertex->b.
     */
    fun angleBetween(a: PointF, vertex: PointF, b: PointF): Float {
        val ax = a.x - vertex.x
        val ay = a.y - vertex.y
        val bx = b.x - vertex.x
        val by = b.y - vertex.y
        val angA = atan2(ay, ax)
        val angB = atan2(by, bx)
        var deg = Math.toDegrees((angA - angB).toDouble()).toFloat()
        deg = abs(deg)
        if (deg > 180f) deg = 360f - deg
        return deg
    }

    /** Horizontal distance (absolute) between two points. */
    fun horizontalOffset(a: PointF, b: PointF): Float = abs(a.x - b.x)

    /** Distance between two points. */
    fun distance(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
