package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meuxo.formcheck.domain.model.JumpTest
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.domain.model.SessionResult
import com.meuxo.formcheck.domain.model.ShotResult
import com.meuxo.formcheck.domain.model.UserSettings
import com.meuxo.formcheck.ui.components.Format

@Composable
fun JumpTestPicker(selected: JumpTest, onSelect: (JumpTest) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Test", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JumpTest.entries.forEach { t -> FilterChip(selected == t, { onSelect(t) }, label = { Text(t.label) }) }
        }
        Text(selected.description, style = MaterialTheme.typography.bodySmall)
        Text("Do up to 3 attempts in one recording; the best one is reported. Recording uses the highest frame rate " +
            "your phone offers (120 or 240 fps when available). Live jump analysis is not offered: high-speed recording " +
            "on Android cannot analyse frames at the same time, so the clip is analysed right after you stop.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun JumpSections(r: SessionResult, settings: UserSettings, vm: SessionDataViewModel, onAttempt: (ShotResult) -> Unit) {
    val history by remember { vm.history(MetricId.JUMP_HEIGHT) }.collectAsState(initial = emptyList())
    val best = r.shots.maxByOrNull { s -> s.metrics.firstOrNull { it.id == MetricId.JUMP_HEIGHT }?.value ?: -1.0 }
    val pb = history.filter { it.shotIndex != null }.maxByOrNull { it.value ?: -1.0 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        r.jumpTest?.let { t -> runCatching { JumpTest.valueOf(t) }.getOrNull()?.let { Text(it.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) } }
        best?.metrics?.firstOrNull { it.id == MetricId.JUMP_HEIGHT }?.value?.let { cm ->
            Text("${"%.1f".format(cm / 2.54)} in · ${"%.1f".format(cm)} cm", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        pb?.value?.let { v ->
            Text("Personal best: ${Format.value(v, com.meuxo.formcheck.domain.model.MetricUnit.CENTIMETERS, settings.units)}" +
                if (pb.sessionId == vm.id) " (this session)" else "", style = MaterialTheme.typography.bodyMedium)
        }
        if (r.shots.size > 1) {
            Text("Attempts", style = MaterialTheme.typography.titleMedium)
            r.shots.forEach { s ->
                val h = s.metrics.firstOrNull { it.id == MetricId.JUMP_HEIGHT }
                Card(onClick = { onAttempt(s) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Attempt ${s.index + 1}: ${h?.let { Format.value(it, settings.units) } ?: "—"}" + if (s == best) " (best)" else "",
                            fontWeight = FontWeight.SemiBold)
                        Text("Tap to step through takeoff and landing frames.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
