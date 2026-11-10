package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.domain.model.SessionResult
import com.meuxo.formcheck.domain.model.ShotOutcome
import com.meuxo.formcheck.domain.model.ShotResult
import com.meuxo.formcheck.domain.model.UserSettings
import com.meuxo.formcheck.shootaround.CourtSpec
import com.meuxo.formcheck.shootaround.ShootaroundAnalyzer
import com.meuxo.formcheck.ui.components.Format
import com.meuxo.formcheck.ui.components.ShotChart

/** Mean of a metric over shots with a given outcome; null if none measured. */
fun meanOf(shots: List<ShotResult>, outcome: ShotOutcome, id: MetricId): Pair<Double, Int>? {
    val v = shots.filter { it.outcome == outcome }.mapNotNull { s -> s.metrics.firstOrNull { it.id == id }?.value }
    return if (v.isEmpty()) null else v.average() to v.size
}

@Composable
fun ShootaroundSections(
    r: SessionResult,
    settings: UserSettings,
    vm: SessionDataViewModel,
    selectShot: (Int) -> Unit,
    onRecalibrate: (fromFrame: Int) -> Unit,
    onReplayShot: (ShotResult) -> Unit
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    val n = r.metrics.firstOrNull { it.id == MetricId.ATTEMPTS }?.value?.toInt() ?: 0
    val makes = r.metrics.firstOrNull { it.id == MetricId.MAKES }
    val fg = r.metrics.firstOrNull { it.id == MetricId.FG_PERCENT }
    val streak = r.metrics.firstOrNull { it.id == MetricId.LONGEST_STREAK }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("${makes?.value?.toInt() ?: 0} of $n made" + (fg?.value?.let { " · ${it.toInt()}%" } ?: ""),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                fg?.notMeasuredReason?.let { Text("FG%: $it") }
                (makes?.note ?: fg?.note)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                streak?.value?.let { Text("Longest make streak: ${it.toInt()}") }
            }
        }

        // Shot chart (only shots with a measured location).
        val params by vm.params.collectAsState()
        val court = params?.court?.lastOrNull()?.courtSize
        if (court != null) {
            Text("Shot chart", style = MaterialTheme.typography.titleMedium)
            ShotChart(CourtSpec.of(court), r.shots, selected, onTap = { i -> selected = i; selectShot(i) })
            Text("● make  ✕ miss  ○ uncertain. Tap a mark to select the shot.", style = MaterialTheme.typography.bodySmall)
            selected?.let { i -> r.shots.getOrNull(i)?.let { s -> Button(onClick = { onReplayShot(s) }) { Text("Replay shot ${i + 1}") } } }
            val unlocated = r.shots.count { it.courtX == null }
            if (unlocated > 0) Text("$unlocated shots have no location: ${r.shots.firstOrNull { it.courtX == null }?.locationReason ?: ""}",
                style = MaterialTheme.typography.bodySmall)
        } else Text("Shot locations: Not measured (court not calibrated).", style = MaterialTheme.typography.bodySmall)

        r.shots.firstOrNull { it.locationReason?.contains("moved") == true }?.let { s ->
            Button(onClick = { onRecalibrate(s.releaseFrame ?: s.startFrame) }) { Text("Recalibrate from the moment the phone moved") }
        }

        val zones = ShootaroundAnalyzer.byZone(r.shots)
        if (zones.isNotEmpty()) {
            Text("By zone", style = MaterialTheme.typography.titleMedium)
            zones.entries.sortedBy { it.key.ordinal }.forEach { (z, mk) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(z.label, Modifier.weight(1f)); Text("${mk.first}/${mk.second} · ${100 * mk.first / mk.second}%")
                }
            }
        }

        Text("Makes vs misses", style = MaterialTheme.typography.titleMedium)
        listOf(MetricId.RELEASE_ANGLE, MetricId.ENTRY_ANGLE, MetricId.SHOT_TIMING).forEach { id ->
            val mk = meanOf(r.shots, ShotOutcome.MAKE, id); val ms = meanOf(r.shots, ShotOutcome.MISS, id)
            Row(Modifier.fillMaxWidth()) {
                Text(id.displayName, Modifier.weight(1f))
                Text("makes ${mk?.let { Format.value(it.first, id.unit, settings.units) + " (n=${it.second})" } ?: "—"} · " +
                    "misses ${ms?.let { Format.value(it.first, id.unit, settings.units) + " (n=${it.second})" } ?: "—"}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Averages only include shots where the metric was measured.", style = MaterialTheme.typography.bodySmall)

        Text("Shots", style = MaterialTheme.typography.titleMedium)
        r.shots.forEach { s ->
            Card(onClick = { selectShot(s.index) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Shot ${s.index + 1}: ${s.outcome?.label ?: "—"}" + (if (s.outcomeCorrected) " (corrected)" else "") +
                            (s.zone?.let { " · ${it.label}" } ?: ""), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    }
                    s.outcomeReason?.takeIf { !s.outcomeCorrected }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.outcome != ShotOutcome.MAKE) AssistChip(onClick = { vm.correctOutcome(s.index, ShotOutcome.MAKE) }, label = { Text("It was a make") })
                        if (s.outcome != ShotOutcome.MISS) AssistChip(onClick = { vm.correctOutcome(s.index, ShotOutcome.MISS) }, label = { Text("It was a miss") })
                        AssistChip(onClick = { onReplayShot(s) }, label = { Text("Replay") })
                    }
                }
            }
        }
    }
}
