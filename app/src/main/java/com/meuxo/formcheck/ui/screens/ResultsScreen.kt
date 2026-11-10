package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.SessionStatus
import com.meuxo.formcheck.ui.components.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    onBack: () -> Unit,
    onPlayback: (frame: Int?, metric: Metric?) -> Unit,
    vm: SessionDataViewModel = hiltViewModel()
) {
    val session by vm.session.collectAsState()
    val result by vm.result.collectAsState()
    val settings by vm.settings.collectAsState()

    Scaffold(topBar = {
        TopAppBar(title = { Text(session?.displayTitle ?: "Results") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }, actions = {
            if (result != null) IconButton(onClick = { onPlayback(null, null) }) { Icon(Icons.Default.PlayCircle, contentDescription = "Play with overlay") }
        })
    }) { padding ->
        val s = session
        val r = result
        if (s?.status == SessionStatus.LEGACY) {
            Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Session from FormCheck v1", style = MaterialTheme.typography.titleLarge)
                Text("v1 scores included placeholder values and are not shown. The original video was not copied into the app, " +
                    "so it may no longer be available. Record or upload it again to analyze it with the new pipeline.")
            }
            return@Scaffold
        }
        if (r == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val metrics = (r.shots.firstOrNull()?.metrics ?: emptyList()) + r.metrics
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item {
                Text(r.overall.percent?.let { "Overall $it%" } ?: "No scored metric could be measured", style = MaterialTheme.typography.headlineMedium)
                Text("Based on ${r.overall.included} of ${r.overall.total} metrics" + (r.view?.let { " · ${it.label} view" } ?: ""))
            }
            items(r.warnings) { Text("• $it", color = MaterialTheme.colorScheme.error) }
            items(metrics) { m ->
                Card(onClick = { onPlayback(m.evidence?.keyFrame, m) }, enabled = m.evidence != null, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.id.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(Format.value(m, settings.units) + (m.score?.let { " · $it%" } ?: ""))
                        m.notMeasuredReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (m.isMeasured) Text("Confidence ${(m.confidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        m.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item { Button(onClick = vm::reanalyze) { Text("Re-analyze") } }
        }
    }
}
