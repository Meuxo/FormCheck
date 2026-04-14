package com.meuxo.formcheck.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight Canvas-based line chart with axes, gridlines, and a linear
 * trend line. Avoids any third-party charting dependency so the project
 * compiles cleanly across versions.
 */
@Composable
fun MetricChart(
    title: String,
    values: List<Float>,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val trendColor = MaterialTheme.colorScheme.secondary
    val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (values.isEmpty()) {
            Text("No data yet", style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        val minVal = values.min()
        val maxVal = values.max()
        val range = (maxVal - minVal).takeIf { it > 0f } ?: 1f
        Text(
            text = "min ${minVal.toInt()}  •  max ${maxVal.toInt()}",
            style = MaterialTheme.typography.bodySmall
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(top = 8.dp)
        ) {
            val padLeft = 32f
            val padRight = 8f
            val padTop = 8f
            val padBottom = 24f
            val w = size.width - padLeft - padRight
            val h = size.height - padTop - padBottom

            // Gridlines (4 horizontal divisions)
            for (i in 0..4) {
                val y = padTop + h * i / 4f
                drawLine(
                    color = gridColor,
                    start = Offset(padLeft, y),
                    end = Offset(padLeft + w, y),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
            }

            // Axes
            drawLine(
                color = axisColor,
                start = Offset(padLeft, padTop),
                end = Offset(padLeft, padTop + h),
                strokeWidth = 2f
            )
            drawLine(
                color = axisColor,
                start = Offset(padLeft, padTop + h),
                end = Offset(padLeft + w, padTop + h),
                strokeWidth = 2f
            )

            if (values.size < 2) {
                // Single point — draw a dot.
                drawCircle(
                    color = lineColor,
                    radius = 6f,
                    center = Offset(padLeft + w / 2f, padTop + h / 2f)
                )
                return@Canvas
            }

            val stepX = w / (values.size - 1).toFloat()
            fun pointAt(i: Int): Offset {
                val v = values[i]
                val ny = (v - minVal) / range
                val y = padTop + h - ny * h
                return Offset(padLeft + i * stepX, y)
            }

            // Data line
            val path = Path().apply {
                moveTo(pointAt(0).x, pointAt(0).y)
                for (i in 1 until values.size) {
                    val p = pointAt(i)
                    lineTo(p.x, p.y)
                }
            }
            drawPath(path, color = lineColor, style = Stroke(width = 4f))

            // Data points
            for (i in values.indices) {
                drawCircle(color = lineColor, radius = 4f, center = pointAt(i))
            }

            // Linear regression trend line
            val n = values.size
            val xs = (0 until n).map { it.toFloat() }
            val meanX = xs.average().toFloat()
            val meanY = values.average().toFloat()
            var num = 0f
            var den = 0f
            for (i in 0 until n) {
                num += (xs[i] - meanX) * (values[i] - meanY)
                den += (xs[i] - meanX) * (xs[i] - meanX)
            }
            val slope = if (den != 0f) num / den else 0f
            val intercept = meanY - slope * meanX
            fun trendAt(i: Int): Offset {
                val v = slope * i + intercept
                val ny = ((v - minVal) / range).coerceIn(0f, 1f)
                val y = padTop + h - ny * h
                return Offset(padLeft + i * stepX, y)
            }
            val trendStart = trendAt(0)
            val trendEnd = trendAt(n - 1)
            drawLine(
                color = trendColor,
                start = trendStart,
                end = trendEnd,
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
            )
        }
    }
}

@Suppress("unused")
private fun rangePad(min: Float, max: Float): Pair<Float, Float> {
    val pad = max(1f, (max - min) * 0.1f)
    return min - pad to max + pad
}

@Suppress("unused")
private fun clamp(v: Float, lo: Float, hi: Float) = min(max(v, lo), hi)

@Suppress("unused")
private fun colorOrFallback(c: Color?, fallback: Color) = c ?: fallback
