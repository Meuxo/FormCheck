package com.meuxo.formcheck.tracking.geom

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Plain-Kotlin vector types used by every analyzer. Deliberately free of
 * android.graphics so all measurement math runs in JVM unit tests.
 *
 * Image coordinates follow the camera convention: x grows right, y grows DOWN.
 */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    operator fun div(s: Double) = Vec2(x / s, y / s)
    fun dot(o: Vec2) = x * o.x + y * o.y
    fun length() = sqrt(x * x + y * y)
    fun distanceTo(o: Vec2) = (this - o).length()

    companion object {
        val ZERO = Vec2(0.0, 0.0)
        fun mid(a: Vec2, b: Vec2) = Vec2((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
    }
}

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun length() = sqrt(x * x + y * y + z * z)

    companion object {
        fun mid(a: Vec3, b: Vec3) = Vec3((a.x + b.x) / 2.0, (a.y + b.y) / 2.0, (a.z + b.z) / 2.0)
    }
}

object Angles {

    /** Interior angle at [vertex] (degrees, 0..180) between vertex→a and vertex→b, in 3D. */
    fun jointAngle(a: Vec3, vertex: Vec3, b: Vec3): Double {
        val u = a - vertex
        val v = b - vertex
        val denom = u.length() * v.length()
        if (denom < 1e-12) return Double.NaN
        val c = (u.dot(v) / denom).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    /** Interior angle at [vertex] (degrees, 0..180) in the image plane. */
    fun jointAngle(a: Vec2, vertex: Vec2, b: Vec2): Double {
        val u = a - vertex
        val v = b - vertex
        val denom = u.length() * v.length()
        if (denom < 1e-12) return Double.NaN
        val c = (u.dot(v) / denom).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    /**
     * Unsigned angle (degrees, 0..180) between the segment from→to and the
     * image vertical pointing UP. 0 means the segment points straight up.
     */
    fun angleFromVerticalUp(from: Vec2, to: Vec2): Double {
        val d = to - from
        if (d.length() < 1e-12) return Double.NaN
        // Up in image coordinates is -y.
        return Math.toDegrees(atan2(abs(d.x), -d.y)).let { if (it < 0) -it else it }
    }

    /**
     * Elevation angle (degrees) of a velocity vector above the horizon in image
     * coordinates (y down). Positive means moving up. Direction (left/right) is ignored.
     */
    fun elevationDeg(vx: Double, vy: Double): Double =
        Math.toDegrees(atan2(-vy, abs(vx)))
}

object Stats {
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    fun mean(values: List<Double>): Double =
        if (values.isEmpty()) Double.NaN else values.sum() / values.size

    /** Sample standard deviation (n-1). NaN for fewer than 2 values. */
    fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return Double.NaN
        val m = mean(values)
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        val idx = (p.coerceIn(0.0, 1.0) * (s.size - 1))
        val lo = idx.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        val f = idx - lo
        return s[lo] * (1 - f) + s[hi] * f
    }
}

/** Small dense linear-algebra helpers (Gaussian elimination with partial pivoting). */
object LinAlg {
    /** Solves A x = b for square A. Returns null if A is singular. A and b are not modified. */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { r -> DoubleArray(n + 1).also { row ->
            for (c in 0 until n) row[c] = a[r][c]
            row[n] = b[r]
        } }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col] / m[col][col]
                if (f == 0.0) continue
                for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    /** Least-squares solution of an over-determined system via the normal equations. */
    fun leastSquares(rows: List<DoubleArray>, rhs: List<Double>): DoubleArray? {
        if (rows.isEmpty()) return null
        val n = rows[0].size
        val ata = Array(n) { DoubleArray(n) }
        val atb = DoubleArray(n)
        rows.forEachIndexed { k, row ->
            for (i in 0 until n) {
                atb[i] += row[i] * rhs[k]
                for (j in 0 until n) ata[i][j] += row[i] * row[j]
            }
        }
        return solve(ata, atb)
    }
}
