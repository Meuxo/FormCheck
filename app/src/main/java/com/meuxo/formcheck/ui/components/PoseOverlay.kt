package com.meuxo.formcheck.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.meuxo.formcheck.domain.model.ArcSummary
import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.OverlayKind
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.PoseFrame
import com.meuxo.formcheck.tracking.RimObs
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.tracking.TrackingConfig
import kotlin.math.atan2

data class OverlayToggles(
    val skeleton: Boolean = true,
    val ballTrail: Boolean = true,
    val angleLabels: Boolean = true
)

/**
 * Draws tracking data for one frame of [clip] on top of the video. Positions are in the
 * clip's analysed-frame pixels and are letter-boxed to the composable exactly like a
 * PlayerView in RESIZE_MODE_FIT, so the overlay lines up with the picture.
 */
@Composable
fun PoseOverlay(
    clip: TrackedClip,
    frameIndex: Int,
    toggles: OverlayToggles,
    modifier: Modifier = Modifier,
    highlight: Metric? = null,
    arcs: List<ArcSummary> = emptyList()
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (clip.width <= 0 || frameIndex !in clip.frames.indices) return@Canvas
        val s = minOf(size.width / clip.width, size.height / clip.height)
        val ox = (size.width - clip.width * s) / 2f
        val oy = (size.height - clip.height * s) / 2f
        fun pt(x: Double, y: Double) = Offset((x * s + ox).toFloat(), (y * s + oy).toFloat())
        val frame = clip.frames[frameIndex]
        val pose = frame.pose

        clip.rim?.let { drawRim(it, ::pt, s) }

        if (toggles.ballTrail) {
            // Trail of the last 0.75 s of tracked ball positions.
            val t0 = frame.timeUs - 750_000
            val trail = clip.frames.subList(0, frameIndex + 1).asReversed()
                .takeWhile { it.timeUs >= t0 }.mapNotNull { it.ball?.takeIf { b -> !b.predicted } }.asReversed()
            trail.forEachIndexed { i, b ->
                val a = (i + 1f) / trail.size
                drawCircle(BALL.copy(alpha = 0.25f + 0.5f * a), radius = 3f + 2f * a, center = pt(b.x.toDouble(), b.y.toDouble()))
            }
            arcs.filter { frame.timeSec >= it.startSec }.forEach { drawArc(it, ::pt) }
            frame.ball?.let { b ->
                drawCircle(BALL, radius = (b.radius * s).coerceAtLeast(4f), center = pt(b.x.toDouble(), b.y.toDouble()),
                    style = Stroke(width = if (b.predicted) 2f else 4f,
                        pathEffect = if (b.predicted) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null))
            }
        }

        if (pose != null && toggles.skeleton) drawSkeleton(pose, ::pt)
        if (pose != null && toggles.angleLabels && highlight != null) drawHighlight(pose, highlight, ::pt)
    }
}

private val BONE = Color(0xFFFFFFFF)
private val HIDDEN = Color(0x66FFFFFF)
private val BALL = Color(0xFFFF8F00)
private val ARC = Color(0xFF4FC3F7)
private val HIGHLIGHT = Color(0xFFFFEB3B)

private fun DrawScope.drawSkeleton(p: PoseFrame, pt: (Double, Double) -> Offset) {
    for ((a, b) in Lm.BONES) {
        val visible = p.visible(a) && p.visible(b)
        val pa = p.px(a); val pb = p.px(b)
        drawLine(if (visible) BONE else HIDDEN, pt(pa.x, pa.y), pt(pb.x, pb.y), strokeWidth = if (visible) 5f else 2f,
            pathEffect = if (visible) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    }
    for (i in listOf(Lm.NOSE, Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, Lm.LEFT_ELBOW, Lm.RIGHT_ELBOW, Lm.LEFT_WRIST,
        Lm.RIGHT_WRIST, Lm.LEFT_HIP, Lm.RIGHT_HIP, Lm.LEFT_KNEE, Lm.RIGHT_KNEE, Lm.LEFT_ANKLE, Lm.RIGHT_ANKLE)) {
        if (!p.visible(i)) continue
        val v = p.px(i)
        drawCircle(Color.Black, 6f, pt(v.x, v.y)); drawCircle(BONE, 4f, pt(v.x, v.y))
    }
}

private fun DrawScope.drawRim(r: RimObs, pt: (Double, Double) -> Offset, s: Float) {
    val c = pt(r.x.toDouble(), r.y.toDouble())
    val w = r.width * s
    drawOval(Color(0xFFFF5252), topLeft = Offset(c.x - w / 2, c.y - w * 0.12f), size = Size(w, w * 0.24f), style = Stroke(3f))
}

private fun DrawScope.drawArc(a: ArcSummary, pt: (Double, Double) -> Offset) {
    val fit = a.toFit()
    val path = Path()
    val steps = 40
    for (k in 0..steps) {
        val t = a.startSec + (a.endSec - a.startSec) * k / steps
        val p = fit.pos(t)
        val o = pt(p.x, p.y)
        if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
    }
    drawPath(path, ARC, style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
}

private fun DrawScope.drawHighlight(p: PoseFrame, m: Metric, pt: (Double, Double) -> Offset) {
    val hint = m.evidence?.overlay ?: return
    val label = buildString {
        append(m.value?.let { "%.0f".format(it) } ?: "—"); append(m.unit.symbol)
    }
    when (hint.kind) {
        OverlayKind.JOINT_ANGLE -> {
            if (hint.landmarks.size < 3) return
            val (a, v, b) = hint.landmarks
            if (!p.allVisible(a, v, b, threshold = TrackingConfig.VISIBILITY_THRESHOLD)) return
            val pa = p.px(a); val pv = p.px(v); val pb = p.px(b)
            val ca = pt(pa.x, pa.y); val cv = pt(pv.x, pv.y); val cb = pt(pb.x, pb.y)
            drawLine(HIGHLIGHT, cv, ca, strokeWidth = 7f); drawLine(HIGHLIGHT, cv, cb, strokeWidth = 7f)
            val r = 40f
            val start = Math.toDegrees(atan2((ca.y - cv.y).toDouble(), (ca.x - cv.x).toDouble())).toFloat()
            val end = Math.toDegrees(atan2((cb.y - cv.y).toDouble(), (cb.x - cv.x).toDouble())).toFloat()
            var sweep = end - start
            if (sweep > 180) sweep -= 360; if (sweep < -180) sweep += 360
            drawArc(HIGHLIGHT, start, sweep, false, topLeft = Offset(cv.x - r, cv.y - r), size = Size(2 * r, 2 * r), style = Stroke(4f))
            drawLabel(label, Offset(cv.x + r + 8, cv.y))
        }
        OverlayKind.LINE_FROM_VERTICAL -> {
            if (hint.landmarks.size < 2) return
            val (a, b) = hint.landmarks
            val pa = p.px(a); val pb = p.px(b)
            val ca = pt(pa.x, pa.y); val cb = pt(pb.x, pb.y)
            drawLine(HIGHLIGHT, ca, cb, strokeWidth = 7f)
            drawLine(HIGHLIGHT.copy(alpha = 0.6f), ca, Offset(ca.x, ca.y - (ca - cb).getDistance()), strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            drawLabel(label, Offset(cb.x + 12, cb.y))
        }
        else -> Unit
    }
}

private fun DrawScope.drawLabel(text: String, at: Offset) {
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE; textSize = 42f; isAntiAlias = true
        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
    }
    drawContext.canvas.nativeCanvas.drawText(text, at.x, at.y, paint)
}
