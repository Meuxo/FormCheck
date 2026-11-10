package com.meuxo.formcheck.tracking

import com.meuxo.formcheck.tracking.geom.LinAlg
import com.meuxo.formcheck.tracking.geom.Vec2
import kotlinx.serialization.Serializable

/**
 * Planar homography (3×3, h33 = 1) mapping image pixels on the floor plane to court
 * coordinates. Estimated with the direct linear transform from ≥ 4 correspondences;
 * with more than 4 it is the least-squares solution. Points are normalised (Hartley)
 * before solving for numerical stability.
 */
@Serializable
data class Homography(val h: List<Double>) {
    init { require(h.size == 9) }

    fun map(p: Vec2): Vec2? {
        val w = h[6] * p.x + h[7] * p.y + h[8]
        if (kotlin.math.abs(w) < 1e-12) return null
        return Vec2((h[0] * p.x + h[1] * p.y + h[2]) / w, (h[3] * p.x + h[4] * p.y + h[5]) / w)
    }

    companion object {
        fun estimate(src: List<Vec2>, dst: List<Vec2>): Homography? {
            if (src.size < 4 || src.size != dst.size) return null
            val (ns, ts) = normalize(src)
            val (nd, td) = normalize(dst)
            val rows = ArrayList<DoubleArray>()
            val rhs = ArrayList<Double>()
            for (i in ns.indices) {
                val (x, y) = ns[i].x to ns[i].y
                val (u, v) = nd[i].x to nd[i].y
                rows += doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y); rhs += u
                rows += doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y); rhs += v
            }
            val sol = if (rows.size == 8) {
                LinAlg.solve(rows.toTypedArray(), rhs.toDoubleArray())
            } else LinAlg.leastSquares(rows, rhs) ?: return null
            sol ?: return null
            val hn = doubleArrayOf(sol[0], sol[1], sol[2], sol[3], sol[4], sol[5], sol[6], sol[7], 1.0)
            // Denormalise: H = Td^-1 · Hn · Ts
            val h = mul(mul(inverseSimilarity(td), hn), ts)
            val s = h[8]
            if (kotlin.math.abs(s) < 1e-12) return null
            return Homography(h.map { it / s })
        }

        /** Returns normalised points and the 3×3 similarity (row-major) that produced them. */
        private fun normalize(pts: List<Vec2>): Pair<List<Vec2>, DoubleArray> {
            val cx = pts.sumOf { it.x } / pts.size
            val cy = pts.sumOf { it.y } / pts.size
            val meanDist = pts.sumOf { Vec2(it.x - cx, it.y - cy).length() } / pts.size
            val s = if (meanDist < 1e-12) 1.0 else kotlin.math.sqrt(2.0) / meanDist
            val t = doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0)
            return pts.map { Vec2((it.x - cx) * s, (it.y - cy) * s) } to t
        }

        private fun inverseSimilarity(t: DoubleArray): DoubleArray {
            val s = t[0]; val tx = t[2]; val ty = t[5]
            return doubleArrayOf(1 / s, 0.0, -tx / s, 0.0, 1 / s, -ty / s, 0.0, 0.0, 1.0)
        }

        private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
            val r = DoubleArray(9)
            for (i in 0 until 3) for (j in 0 until 3) {
                var s = 0.0
                for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
                r[i * 3 + j] = s
            }
            return r
        }
    }
}
