package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meuxo.formcheck.data.local.MetricEntity
import com.meuxo.formcheck.data.settings.SettingsRepository
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.domain.model.UserSettings
import com.meuxo.formcheck.domain.repository.SessionRepository
import com.meuxo.formcheck.ui.components.Format
import com.meuxo.formcheck.ui.components.MetricChart
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One point per session: the mean of that metric's measured values in the session. */
data class TrendPoint(val sessionId: Long, val timestamp: Long, val value: Double)

fun trend(rows: List<MetricEntity>, id: MetricId): List<TrendPoint> =
    rows.filter { it.metricId == id.name && it.value != null }
        .groupBy { it.sessionId }
        .map { (sid, l) -> TrendPoint(sid, l.first().timestamp, l.mapNotNull { it.value }.average()) }
        .sortedBy { it.timestamp }

@HiltViewModel
class ProgressViewModel @Inject constructor(repo: SessionRepository, settings: SettingsRepository) : ViewModel() {
    val metrics = repo.observeAllMeasuredMetrics().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(onBack: () -> Unit, vm: ProgressViewModel = hiltViewModel()) {
    val rows by vm.metrics.collectAsState()
    val settings by vm.settings.collectAsState()
    val available = MetricId.entries.filter { id -> rows.any { it.metricId == id.name } }
    var selected by remember { mutableStateOf<MetricId?>(null) }
    val current = selected ?: available.firstOrNull()

    Scaffold(topBar = {
        TopAppBar(title = { Text("Progress") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (current == null) {
                item { Text("No measured metrics yet. Analyze a session to start tracking trends.") }
                return@LazyColumn
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    available.forEach { id -> FilterChip(id == current, { selected = id }, label = { Text(id.displayName) }) }
                }
            }
            val pts = trend(rows, current)
            item {
                MetricChart("${current.displayName} (${current.unit.symbol.ifEmpty { "count" }})", pts.map { it.value.toFloat() })
                Text("${pts.size} sessions · latest ${pts.lastOrNull()?.let { Format.value(it.value, current.unit, settings.units) } ?: "—"}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
