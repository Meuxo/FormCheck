package com.meuxo.formcheck.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.Units
import com.meuxo.formcheck.scoring.OverallScore
import com.meuxo.formcheck.scoring.ScoringConfig
import com.meuxo.formcheck.scoring.TargetRange
import com.meuxo.formcheck.ui.theme.ScoreBad
import com.meuxo.formcheck.ui.theme.ScoreGood
import com.meuxo.formcheck.ui.theme.ScoreNone
import com.meuxo.formcheck.ui.theme.ScoreWarn

enum class Band(val color: Color, val icon: ImageVector, val word: String) {
    GOOD(ScoreGood, Icons.Default.CheckCircle, "good"),
    OK(ScoreWarn, Icons.Default.Warning, "needs work"),
    LOW(ScoreBad, Icons.Default.Error, "focus area"),
    INFO(ScoreNone, Icons.Default.Info, "no target"),
    NONE(ScoreNone, Icons.Default.HelpOutline, "not measured");

    companion object {
        fun of(m: Metric): Band = when {
            !m.isMeasured -> NONE
            m.score == null -> INFO
            else -> of(m.score)
        }
        fun of(score: Int): Band = when { score >= 80 -> GOOD; score >= 60 -> OK; else -> LOW }
    }
}

/**
 * The reusable results dashboard (spec 4): overall ring, biggest-fix card, one row per metric.
 * Used for form shot, shootaround and dribbling results.
 */
@Composable
fun ScoreDashboard(
    overall: OverallScore,
    metrics: List<Metric>,
    biggestFix: Metric?,
    biggestFixTip: String?,
    units: Units,
    onMetricTap: (Metric) -> Unit,
    modifier: Modifier = Modifier,
    rangeFor: (Metric) -> TargetRange? = { ScoringConfig.targets[it.id] }
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverallRing(overall, Modifier.align(Alignment.CenterHorizontally))
        if (biggestFix != null) BiggestFixCard(biggestFix, biggestFixTip, units) { onMetricTap(biggestFix) }
        metrics.forEach { m -> MetricRow(m, units, rangeFor(m), onTap = { onMetricTap(m) }) }
    }
}

@Composable
fun OverallRing(o: OverallScore, modifier: Modifier = Modifier, size: Int = 168) {
    val pct = o.percent
    val color = pct?.let { Band.of(it).color } ?: ScoreNone
    val track = MaterialTheme.colorScheme.surfaceVariant
    val desc = if (pct == null) "Overall score not available: no scored metric was measured"
    else "Overall score $pct percent, ${Band.of(pct).word}, from ${o.included} of ${o.total} metrics"
    Column(modifier.semantics(mergeDescendants = true) {}.clearAndSetSemantics { contentDescription = desc },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size.dp)) {
                val stroke = 18.dp.toPx()
                drawArc(track, -90f, 360f, false, style = Stroke(stroke))
                if (pct != null) drawArc(color, -90f, 360f * pct / 100f, false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(pct?.let { "$it%" } ?: "—", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                if (pct != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Band.of(pct).icon, null, tint = color, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(Band.of(pct).word, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(if (o.total == 0) "No scored metrics for this session" else "Overall from ${o.included} of ${o.total} metrics",
            style = MaterialTheme.typography.bodyMedium)
        if (o.included < o.total) Text("Metrics that could not be measured are left out, not guessed.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun BiggestFixCard(m: Metric, tip: String?, units: Units, onTap: () -> Unit) {
    Card(onClick = onTap, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Biggest fix", style = MaterialTheme.typography.labelLarge)
                Text("${m.id.displayName}: ${Format.value(m, units)} (${m.score}%)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                tip?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text("Tap to see the frame", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun MetricRow(m: Metric, units: Units, range: TargetRange?, onTap: () -> Unit) {
    val band = Band.of(m)
    var help by remember { mutableStateOf(false) }
    val valueText = Format.value(m, units)
    val desc = buildString {
        append("${m.id.displayName}: $valueText")
        m.score?.let { append(", $it percent, ${band.word}") }
        if (m.isMeasured) append(", confidence ${(m.confidence * 100).toInt()} percent")
        m.notMeasuredReason?.let { append(". $it") }
    }
    Card(onClick = onTap, enabled = m.evidence != null, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(band.icon, contentDescription = null, tint = band.color, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = desc }) {
                    Text(m.id.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(valueText + (m.score?.let { " · $it%" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                }
                if (m.isMeasured) ConfidenceDots(m.confidence)
                IconButton(onClick = { help = true }) { Icon(Icons.Default.Info, contentDescription = "About ${m.id.displayName}") }
            }
            if (m.score != null) ScoreBar(m.score, band.color)
            m.notMeasuredReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (m.isMeasured && m.score == null) Text("No target range: shown so you can track it over time.", style = MaterialTheme.typography.bodySmall)
            m.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(m.id.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.id.description)
                if (range != null) {
                    Text("Target: ${Format.value(range.low, m.unit, units)} to ${Format.value(range.high, m.unit, units)}", fontWeight = FontWeight.SemiBold)
                    Text("Source: ${range.kind.label}.", fontWeight = FontWeight.SemiBold)
                    Text(range.source, style = MaterialTheme.typography.bodySmall)
                    Text("Score: 100% inside the range, falling to 0% at ${Format.value(range.falloff, m.unit, units).replace("-", "")} outside it.",
                        style = MaterialTheme.typography.bodySmall)
                } else Text("This metric has no target range, so it is not scored.", style = MaterialTheme.typography.bodySmall)
                if (m.isMeasured) Text("Confidence ${(m.confidence * 100).toInt()}%: combines landmark visibility, camera angle and fit quality.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { help = false }) { Text("OK") } })
}

@Composable
private fun ScoreBar(score: Int, color: Color) {
    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(score / 100f).clip(RoundedCornerShape(4.dp)).background(color))
    }
}

/** Three dots: filled count shows confidence (≥ 0.75 three, ≥ 0.45 two, else one). */
@Composable
fun ConfidenceDots(conf: Float) {
    val n = when { conf >= 0.75f -> 3; conf >= 0.45f -> 2; else -> 1 }
    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            Box(Modifier.size(7.dp).clip(CircleShape).background(
                if (i < n) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        }
    }
}
