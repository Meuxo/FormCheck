package com.meuxo.formcheck.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.meuxo.formcheck.domain.model.ShotOutcome
import com.meuxo.formcheck.domain.model.ShotResult
import com.meuxo.formcheck.shootaround.CourtSpec
import com.meuxo.formcheck.tracking.geom.Vec2
import com.meuxo.formcheck.ui.theme.ScoreBad
import com.meuxo.formcheck.ui.theme.ScoreGood
import com.meuxo.formcheck.ui.theme.ScoreNone
import kotlin.math.acos
import kotlin.math.hypot

private const val COURT_W = 50.0
private const val COURT_L = 47.0

/** Court feet → canvas pixels. Baseline at the top, +x (shooter's left) to the right. */
private fun DrawScope.toCanvas(p: Vec2) = Offset(((p.x + COURT_W / 2) / COURT_W * size.width).toFloat(), (p.y / COURT_L * size.height).toFloat())

private fun DrawScope.drawCourtLines(c: CourtSpec, line: Color) {
    val s = Stroke(3f)
    drawRect(line, Offset.Zero, size, style = s)
    val laneTl = toCanvas(Vec2(-c.laneWidth / 2, 0.0)); val laneBr = toCanvas(Vec2(c.laneWidth / 2, c.freeThrowLineY))
    drawRect(line, laneTl, Size(laneBr.x - laneTl.x, laneBr.y - laneTl.y), style = s)
    val hoop = toCanvas(c.hoop)
    drawCircle(line, (0.75 / COURT_W * size.width).toFloat(), hoop, style = s)
    val ftR = (6.0 / COURT_W * size.width).toFloat()
    drawArc(line, 0f, 180f, false, Offset(hoop.x - ftR, toCanvas(Vec2(0.0, c.freeThrowLineY)).y - ftR), Size(2 * ftR, 2 * ftR), style = s)
    // Three-point line: corner straights, then the arc.
    val path = Path()
    val cornerEnd = toCanvas(Vec2(c.cornerThree, c.cornerStraightLength))
    path.moveTo(toCanvas(Vec2(c.cornerThree, 0.0)).x, 0f); path.lineTo(cornerEnd.x, cornerEnd.y)
    val startAng = acos((c.cornerThree / c.threeRadius).coerceIn(-1.0, 1.0))
    val steps = 48
    for (i in 0..steps) {
        val a = startAng + (Math.PI - 2 * startAng) * i / steps
        val p = Vec2(c.threeRadius * kotlin.math.cos(a), c.hoop.y + c.threeRadius * kotlin.math.sin(a))
        if (p.y < c.cornerStraightLength) continue
        val o = toCanvas(p); path.lineTo(o.x, o.y)
    }
    val other = toCanvas(Vec2(-c.cornerThree, c.cornerStraightLength))
    path.lineTo(other.x, other.y); path.lineTo(other.x, 0f)
    drawPath(path, line, style = s)
}

/** Court diagram with one highlighted point (calibration guide). */
@Composable
fun CourtGuide(c: CourtSpec, highlight: Vec2?, done: List<Vec2>, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().aspectRatio((COURT_W / COURT_L).toFloat())) {
        drawRect(Color(0xFFD7A86E))
        drawCourtLines(c, Color.White)
        done.forEach { drawCircle(Color(0xFF2E7D32), 10f, toCanvas(it)) }
        highlight?.let { drawCircle(Color(0xFFFFEB3B), 16f, toCanvas(it)); drawCircle(Color.Black, 16f, toCanvas(it), style = Stroke(3f)) }
    }
}

/**
 * Shot chart (spec 6). Makes: filled green circle. Misses: red X. Uncertain: gray ring.
 * Shapes differ so colour is not the only signal. Tapping a mark selects that shot.
 */
@Composable
fun ShotChart(c: CourtSpec, shots: List<ShotResult>, selected: Int?, onTap: (Int) -> Unit, modifier: Modifier = Modifier) {
    val located = shots.filter { it.courtX != null && it.courtY != null }
    val makes = located.count { it.outcome == ShotOutcome.MAKE }
    Canvas(modifier.fillMaxWidth().aspectRatio((COURT_W / COURT_L).toFloat())
        .semantics { contentDescription = "Shot chart: ${located.size} located shots, $makes makes. Tap a mark to replay that shot." }
        .pointerInput(located) {
            detectTapGestures { pos ->
                val hit = located.minByOrNull { s ->
                    val x = ((s.courtX!! + COURT_W / 2) / COURT_W * size.width); val y = s.courtY!! / COURT_L * size.height
                    hypot(x - pos.x, y - pos.y)
                } ?: return@detectTapGestures
                onTap(hit.index)
            }
        }) {
        drawRect(Color(0xFFD7A86E))
        drawCourtLines(c, Color.White)
        located.forEach { s ->
            val o = toCanvas(Vec2(s.courtX!!, s.courtY!!))
            val sel = s.index == selected
            val r = if (sel) 16f else 11f
            when (s.outcome) {
                ShotOutcome.MAKE -> drawCircle(ScoreGood, r, o)
                ShotOutcome.MISS -> {
                    drawLine(ScoreBad, Offset(o.x - r, o.y - r), Offset(o.x + r, o.y + r), strokeWidth = 6f)
                    drawLine(ScoreBad, Offset(o.x - r, o.y + r), Offset(o.x + r, o.y - r), strokeWidth = 6f)
                }
                else -> drawCircle(ScoreNone, r, o, style = Stroke(5f))
            }
            if (sel) drawCircle(Color.Black, r + 6f, o, style = Stroke(3f))
        }
    }
}
