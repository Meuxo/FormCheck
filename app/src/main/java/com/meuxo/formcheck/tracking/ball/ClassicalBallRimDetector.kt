package com.meuxo.formcheck.tracking.ball

import com.meuxo.formcheck.tracking.BallSource
import com.meuxo.formcheck.tracking.RimObs
import com.meuxo.formcheck.tracking.RimSource
import com.meuxo.formcheck.tracking.geom.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Non-AI ball and rim detector.
 *
 * Ball: HSV colour segmentation for basketball orange → connected components → shape tests
 * (aspect, fill ratio of the bounding ellipse, algebraic circle fit residual) → motion gating
 * (a static orange object such as a cone or a shirt logo that never moves is rejected unless
 * it sits where the tracker expects the ball).
 *
 * Rim: orange/red, wide and thin component (a rim seen from the side is a flat ellipse).
 *
 * All thresholds are HEURISTIC and documented inline. They are tuned for indoor and outdoor
 * daylight; very warm lighting or an orange floor will reduce accuracy, which the confidence
 * of the downstream metrics reflects through the arc fit residual.
 */
class ClassicalBallRimDetector : BallRimDetector {

    private var prevMask: BooleanArray? = null
    private var prevW = 0
    private var prevH = 0

    override fun detectBall(frame: RgbImage, timeUs: Long, hint: Vec2?): List<BallCandidate> {
        val w = frame.width
        val h = frame.height
        val mask = BooleanArray(w * h)
        for (i in 0 until w * h) {
            val c = frame.pixels[i]
            mask[i] = isBallOrange((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        }
        // Motion gate on the colour mask itself: a pixel "moved" if it was not ball-coloured on
        // the previous frame. Brightness differences miss an orange ball over a grey floor.
        val prev = prevMask.takeIf { prevW == w && prevH == h }
        prevMask = mask; prevW = w; prevH = h

        val minArea = max(12, (w * h) / 40000)
        val out = ArrayList<BallCandidate>()
        for (comp in components(mask, w, h, minArea)) {
            val bw = comp.maxX - comp.minX + 1
            val bh = comp.maxY - comp.minY + 1
            val aspect = min(bw, bh).toDouble() / max(bw, bh)
            if (aspect < 0.55) continue
            val ellipseArea = PI * bw * bh / 4.0
            val fill = comp.area / ellipseArea
            if (fill < 0.5 || fill > 1.2) continue
            val circle = fitCircle(comp.boundary) ?: continue
            val relResidual = circle.third / max(circle.second, 1.0)
            if (relResidual > 0.18) continue
            if (circle.second > min(w, h) * 0.25) continue

            var motion = 0.0
            if (prev != null) {
                var moving = 0
                for (idx in comp.pixels) if (!prev[idx]) moving++
                motion = moving.toDouble() / comp.area
            }
            val nearHint = hint != null && hint.distanceTo(Vec2(circle.first.x, circle.first.y)) < circle.second * 4
            if (prev != null && motion < 0.08 && !nearHint) continue

            val shape = (1.0 - relResidual / 0.18) * 0.5 + aspect * 0.3 + (1.0 - abs(1.0 - fill)) * 0.2
            val score = (0.55 * shape + 0.25 * min(1.0, motion * 3) + if (nearHint) 0.2 else 0.0).coerceIn(0.0, 1.0)
            out += BallCandidate(circle.first.x, circle.first.y, circle.second, score * 0.8, BallSource.CLASSICAL)
        }
        return out.sortedByDescending { it.score }
    }

    override fun detectRim(frame: RgbImage): RimObs? {
        val w = frame.width
        val h = frame.height
        val mask = BooleanArray(w * h)
        for (i in 0 until w * h) {
            val c = frame.pixels[i]
            mask[i] = isRimColor((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        }
        var best: RimObs? = null
        var bestScore = 0.0
        for (comp in components(mask, w, h, max(10, (w * h) / 60000))) {
            val bw = comp.maxX - comp.minX + 1
            val bh = comp.maxY - comp.minY + 1
            val elongation = bw.toDouble() / bh
            if (elongation < 2.5) continue                 // rim seen edge-on is wide and thin
            if (bw < w * 0.02 || bw > w * 0.35) continue   // plausible on-screen size
            val cy = (comp.minY + comp.maxY) / 2.0
            if (cy > h * 0.7) continue                     // rims are above the shooter's waist
            val score = min(1.0, elongation / 6.0) * 0.6 + (1.0 - cy / h) * 0.4
            if (score > bestScore) {
                bestScore = score
                best = RimObs(
                    x = ((comp.minX + comp.maxX) / 2.0).toFloat(),
                    y = cy.toFloat(),
                    width = bw.toFloat(),
                    confidence = (score * 0.7).toFloat(),
                    source = RimSource.CLASSICAL
                )
            }
        }
        return best
    }

    companion object {
        /** Basketball leather: hue ≈ 5–30°, saturated, not too dark. */
        fun isBallOrange(r: Int, g: Int, b: Int): Boolean {
            val (hue, s, v) = hsv(r, g, b)
            return hue in 5.0..30.0 && s >= 0.45 && v >= 0.25
        }

        /** Painted rims: red to orange. */
        fun isRimColor(r: Int, g: Int, b: Int): Boolean {
            val (hue, s, v) = hsv(r, g, b)
            return (hue <= 25.0 || hue >= 340.0) && s >= 0.5 && v >= 0.3
        }

        fun hsv(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
            val rf = r / 255.0; val gf = g / 255.0; val bf = b / 255.0
            val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf))
            val d = mx - mn
            val hue = when {
                d == 0.0 -> 0.0
                mx == rf -> 60 * (((gf - bf) / d) % 6)
                mx == gf -> 60 * (((bf - rf) / d) + 2)
                else -> 60 * (((rf - gf) / d) + 4)
            }.let { if (it < 0) it + 360 else it }
            val s = if (mx == 0.0) 0.0 else d / mx
            return Triple(hue, s, mx)
        }

        class Component(
            val pixels: IntArray, val area: Int,
            val minX: Int, val maxX: Int, val minY: Int, val maxY: Int,
            val boundary: List<Vec2>
        )

        /** 4-connected components of [mask] with at least [minArea] pixels. */
        fun components(mask: BooleanArray, w: Int, h: Int, minArea: Int): List<Component> {
            val seen = BooleanArray(mask.size)
            val result = ArrayList<Component>()
            val stack = IntArray(mask.size)
            for (start in mask.indices) {
                if (!mask[start] || seen[start]) continue
                var sp = 0
                stack[sp++] = start; seen[start] = true
                val pix = ArrayList<Int>()
                var minX = w; var maxX = 0; var minY = h; var maxY = 0
                val boundary = ArrayList<Vec2>()
                while (sp > 0) {
                    val idx = stack[--sp]
                    pix += idx
                    val x = idx % w; val y = idx / w
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                    var edge = false
                    for (n in 0 until 4) {
                        val nx = x + if (n == 0) 1 else if (n == 1) -1 else 0
                        val ny = y + if (n == 2) 1 else if (n == 3) -1 else 0
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) { edge = true; continue }
                        val ni = ny * w + nx
                        if (!mask[ni]) { edge = true; continue }
                        if (!seen[ni]) { seen[ni] = true; stack[sp++] = ni }
                    }
                    if (edge) boundary += Vec2(x + 0.5, y + 0.5)
                }
                if (pix.size >= minArea) {
                    result += Component(pix.toIntArray(), pix.size, minX, maxX, minY, maxY, boundary)
                }
            }
            return result
        }

        /**
         * Algebraic (Kåsa) least-squares circle fit. Returns centre, radius, and RMS radial
         * residual, or null if degenerate.
         */
        fun fitCircle(points: List<Vec2>): Triple<Vec2, Double, Double>? {
            if (points.size < 5) return null
            val mx = points.sumOf { it.x } / points.size
            val my = points.sumOf { it.y } / points.size
            var suu = 0.0; var svv = 0.0; var suv = 0.0
            var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
            for (p in points) {
                val u = p.x - mx; val v = p.y - my
                suu += u * u; svv += v * v; suv += u * v
                suuu += u * u * u; svvv += v * v * v; suvv += u * v * v; svuu += v * u * u
            }
            val det = suu * svv - suv * suv
            if (abs(det) < 1e-9) return null
            val b1 = 0.5 * (suuu + suvv)
            val b2 = 0.5 * (svvv + svuu)
            val uc = (b1 * svv - b2 * suv) / det
            val vc = (suu * b2 - suv * b1) / det
            val r = sqrt(uc * uc + vc * vc + (suu + svv) / points.size)
            val c = Vec2(uc + mx, vc + my)
            val rms = sqrt(points.sumOf { val d = it.distanceTo(c) - r; d * d } / points.size)
            return Triple(c, r, rms)
        }
    }
}
