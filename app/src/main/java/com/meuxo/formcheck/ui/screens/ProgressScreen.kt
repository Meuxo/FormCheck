package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meuxo.formcheck.data.local.MetricEntity
import com.meuxo.formcheck.data.settings.SettingsRepository
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.domain.model.Session
import com.meuxo.formcheck.domain.model.UserSettings
import com.meuxo.formcheck.domain.repository.SessionRepository
import com.meuxo.formcheck.scoring.MetricPoint
import com.meuxo.formcheck.scoring.ProgressStats
import com.meuxo.formcheck.scoring.WeeklySummary
import com.meuxo.formcheck.ui.components.Format
import com.meuxo.formcheck.ui.components.MetricChart
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One point per session: the mean of that metric's measured values in the session. */
data class TrendPoint(val sessionId: Long, val timestamp: Long, val value: Double)

fun trend(rows: List<MetricEntity>, id: MetricId): List<TrendPoint> =
    rows.filter { it.metricId == id.name && it.value != null }
        .groupBy { it.sessionId }
        .map { (sid, l) -> TrendPoint(sid, l.first().timestamp, l.mapNotNull { it.value }.average()) }
        .sortedBy { it.timestamp }

fun MetricEntity.toPoint(): MetricPoint? {
    val id = runCatching { MetricId.valueOf(metricId) }.getOrNull() ?: return null
    return MetricPoint(sessionId, timestamp, id, value ?: return null, score)
}

data class ProgressData(val rows: List<MetricEntity>, val sessions: List<Session>, val settings: UserSettings) {
    val points by lazy { rows.mapNotNull { it.toPoint() } }
    val weekly: WeeklySummary by lazy {
        ProgressStats.weekly(points, sessions.associate { it.id to (it.timestamp to it.overallScore) }, settings.weeklyMakesGoal)
    }
    val streak by lazy { ProgressStats.dayStreak(sessions.map { it.timestamp }) }
}

@HiltViewModel
class ProgressViewModel @Inject constructor(repo: SessionRepository, settings: SettingsRepository) : ViewModel() {
    val data = combine(repo.observeAllMeasuredMetrics(), repo.observeAll(), settings.settings) { r, s, st -> ProgressData(r, s, st) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProgressData(emptyList(), emptyList(), UserSettings()))
}

@Composable
fun WeeklyCard(d: ProgressData) {
    val w = d.weekly
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("This week", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${w.sessions} sessions" + (w.avgOverall?.let { " · average score $it%" } ?: "") +
                if (w.attempts > 0) " · ${w.makes}/${w.attempts} made" else "")
            Text("Makes goal: ${w.makes} of ${d.settings.weeklyMakesGoal}")
            LinearProgressIndicator(progress = { w.goalProgress.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(if (d.streak > 0) "${d.streak}-day streak" else "No current streak: train today to start one.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(onBack: () -> Unit, vm: ProgressViewModel = hiltViewModel()) {
    val d by vm.data.collectAsState()
    val available = MetricId.entries.filter { id -> d.rows.any { it.metricId == id.name } }
    var selected by remember { mutableStateOf<MetricId?>(null) }
    val current = selected ?: available.firstOrNull()
    val bests = remember(d.rows) { ProgressStats.personalBests(d.points) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Progress") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item { WeeklyCard(d) }
            if (current == null) {
                item { Text("No measured metrics yet. Analyze a session to start tracking trends.") }
                return@LazyColumn
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    available.forEach { id -> FilterChip(id == current, { selected = id }, label = { Text(id.displayName) }) }
                }
            }
            val pts = trend(d.rows, current)
            item {
                MetricChart("${current.displayName} (${current.unit.symbol.ifEmpty { "count" }})", pts.map { it.value.toFloat() })
                Text("${pts.size} sessions · latest ${pts.lastOrNull()?.let { Format.value(it.value, current.unit, d.settings.units) } ?: "—"}" +
                    (bests[current]?.let { " · best ${Format.value(it.value, current.unit, d.settings.units)} on ${Format.shortDate(it.timestamp)}" } ?: " · no single best for this metric"),
                    style = MaterialTheme.typography.bodySmall)
            }
            item { Text("Personal bests", style = MaterialTheme.typography.titleMedium) }
            items(bests.entries.sortedBy { it.key.ordinal }.toList()) { (id, p) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(id.displayName, Modifier.weight(1f))
                    Text("${Format.value(p.value, id.unit, d.settings.units)} · ${Format.shortDate(p.timestamp)}")
                }
            }
        }
    }
}
