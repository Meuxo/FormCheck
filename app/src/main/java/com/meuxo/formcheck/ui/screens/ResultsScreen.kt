package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.SessionResult
import com.meuxo.formcheck.domain.model.SessionStatus
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.domain.model.UserSettings
import com.meuxo.formcheck.scoring.FixTips
import com.meuxo.formcheck.scoring.MetricScorer
import com.meuxo.formcheck.ui.components.MetricRow
import com.meuxo.formcheck.ui.components.ScoreDashboard
import com.meuxo.formcheck.scoring.ScoringConfig

/**
 * Results for any session type. The score dashboard is shared; type-specific sections
 * (shot chart, drill details, jump attempts) are added by [typeSections].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    onBack: () -> Unit,
    onPlayback: (frame: Int?, metric: Metric?, shot: Int?) -> Unit,
    onCompare: () -> Unit = {},
    vm: SessionDataViewModel = hiltViewModel(),
    typeSections: @Composable (SessionResult, UserSettings, SessionDataViewModel, onShot: (Int) -> Unit) -> Unit = { _, _, _, _ -> }
) {
    val session by vm.session.collectAsState()
    val result by vm.result.collectAsState()
    val settings by vm.settings.collectAsState()
    var shot by remember { mutableIntStateOf(0) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(session?.displayTitle ?: "Results") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }, actions = {
            if (result != null) IconButton(onClick = { onPlayback(result!!.shots.getOrNull(shot)?.startFrame, null, shot) }) {
                Icon(Icons.Default.PlayCircle, contentDescription = "Play video with overlay")
            }
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
        val perShot = r.type == SessionType.FORM || r.type == SessionType.SHOOTAROUND
        val shotMetrics = if (perShot) r.shots.getOrNull(shot)?.metrics.orEmpty() else emptyList()
        // Session-level metrics are the dashboard for dribble/jump; for shots they sit below.
        val dashMetrics = if (perShot) shotMetrics else r.metrics
        val overall = if (perShot) (r.shots.getOrNull(shot)?.overall ?: MetricScorer.overall(shotMetrics)) else r.overall
        val fix = MetricScorer.biggestFix(if (perShot) shotMetrics + r.metrics.filter { it.id.name.startsWith("CONSISTENCY") } else r.metrics)

        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item {
                Text(buildString {
                    append("${r.type.label} · ${r.fps.toInt()} fps")
                    r.view?.let { append(" · ${it.label} view (${(r.viewConfidence * 100).toInt()}% of frames)") }
                    r.shootingHand?.let { append(" · ${it.label().lowercase()} hand") }
                }, style = MaterialTheme.typography.bodyMedium)
                r.scaleNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (r.warnings.isNotEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null); Text("  What limited this analysis", fontWeight = FontWeight.SemiBold)
                        }
                        r.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item { typeSections(r, settings, vm) { shot = it } }
            if (perShot && r.shots.size > 1) item {
                Text("Shot ${shot + 1} of ${r.shots.size}", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.shots.forEachIndexed { i, sh ->
                        FilterChip(i == shot, { shot = i }, label = {
                            Text("${i + 1}" + (sh.outcome?.let { " ${it.label}" } ?: "") + (sh.overall?.percent?.let { " · $it%" } ?: ""))
                        })
                    }
                }
                if (r.shots.size >= 2) OutlinedButton(onClick = onCompare) { Text("Best vs worst shot") }
            }
            item {
                ScoreDashboard(
                    overall = overall, metrics = dashMetrics, biggestFix = fix,
                    biggestFixTip = fix?.let { FixTips.tip(it) } ?: r.biggestFixTip.takeIf { !perShot },
                    units = settings.units,
                    onMetricTap = { m -> onPlayback(m.evidence?.keyFrame, m, m.shotIndex) }
                )
            }
            val recs = com.meuxo.formcheck.scoring.DrillRecommendations.forMetrics(dashMetrics + r.metrics)
            if (recs.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recommended drills", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        recs.forEach { (m, d) ->
                            Text("${d.name} (for ${m.id.displayName.lowercase()}, ${m.score}%)", fontWeight = FontWeight.SemiBold)
                            Text(d.description, style = MaterialTheme.typography.bodySmall)
                            Text(d.reps, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (perShot && r.metrics.isNotEmpty()) {
                item { Text("Across the session", style = MaterialTheme.typography.titleMedium) }
                r.metrics.forEach { m ->
                    item { MetricRow(m, settings.units, ScoringConfig.targets[m.id], onTap = { onPlayback(m.evidence?.keyFrame, m, m.shotIndex) }) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::reanalyze) { Text("Re-analyze") }
                }
            }
        }
    }
}
