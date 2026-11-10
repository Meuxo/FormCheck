package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meuxo.formcheck.data.settings.SettingsRepository
import com.meuxo.formcheck.domain.model.CourtSize
import com.meuxo.formcheck.domain.model.Units
import com.meuxo.formcheck.domain.model.UserSettings
import com.meuxo.formcheck.domain.repository.SessionRepository
import com.meuxo.formcheck.tracking.Side
import com.meuxo.formcheck.ui.components.Format
import com.meuxo.formcheck.work.AnalysisScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppSettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val sessions: SessionRepository,
    private val scheduler: AnalysisScheduler
) : ViewModel() {
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())
    val bytes = sessions.observeTotalBytes().stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val count = sessions.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun update(t: (UserSettings) -> UserSettings) = viewModelScope.launch { settingsRepo.update(t) }
    fun deleteAll() = viewModelScope.launch {
        sessions.observeAll().first().forEach { scheduler.cancel(it.id); sessions.delete(it.id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: AppSettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    val bytes by vm.bytes.collectAsState()
    val all by vm.count.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var heightText by remember(s.heightCm, s.units) { mutableStateOf(s.heightCm?.let { if (s.units == Units.METRIC) "%.0f".format(it) else "%.1f".format(it / 2.54) } ?: "") }
    var reachText by remember(s.standingReachCm, s.units) { mutableStateOf(s.standingReachCm?.let { if (s.units == Units.METRIC) "%.0f".format(it) else "%.1f".format(it / 2.54) } ?: "") }
    var goalText by remember(s.weeklyMakesGoal) { mutableStateOf(s.weeklyMakesGoal.toString()) }
    val unitWord = if (s.units == Units.METRIC) "cm" else "in"
    fun toCm(t: String) = t.toDoubleOrNull()?.let { if (s.units == Units.METRIC) it else it * 2.54 }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("Units") { Units.entries.forEach { u -> FilterChip(s.units == u, { vm.update { it.copy(units = u) } }, label = { Text(if (u == Units.METRIC) "Metric" else "Imperial") }) } }
            Section("Shooting hand") { Side.entries.forEach { h -> FilterChip(s.shootingHand == h, { vm.update { it.copy(shootingHand = h) } }, label = { Text(h.label()) }) } }
            OutlinedTextField(heightText, { heightText = it; toCm(it)?.takeIf { c -> c in 100.0..250.0 }?.let { c -> vm.update { st -> st.copy(heightCm = c) } } },
                label = { Text("Height ($unitWord)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Used for real-distance scale when the ball size can't be used.") })
            OutlinedTextField(reachText, { reachText = it; vm.update { st -> st.copy(standingReachCm = toCm(it)?.takeIf { c -> c in 120.0..320.0 }) } },
                label = { Text("Standing reach ($unitWord, optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Lets the jump test estimate your max reach.") })
            Section("Ball size") { listOf(7, 6, 5).forEach { b -> FilterChip(s.ballSize == b, { vm.update { it.copy(ballSize = b) } }, label = { Text("Size $b") }) } }
            Section("Court") { CourtSize.entries.forEach { c -> FilterChip(s.courtSize == c, { vm.update { it.copy(courtSize = c) } }, label = { Text(c.label) }) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Voice cues in live mode", style = MaterialTheme.typography.titleSmall); Text("Short spoken tips, at most one every 4 seconds.", style = MaterialTheme.typography.bodySmall) }
                Switch(s.voiceCues, { v -> vm.update { it.copy(voiceCues = v) } })
            }
            OutlinedTextField(goalText, { goalText = it; it.toIntOrNull()?.takeIf { g -> g in 1..10_000 }?.let { g -> vm.update { st -> st.copy(weeklyMakesGoal = g) } } },
                label = { Text("Weekly makes goal") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            Text("${all.size} sessions use ${Format.bytes(bytes)} on this phone. Videos are stored only in the app's private storage.")
            OutlinedButton(onClick = { confirmDelete = true }, enabled = all.isNotEmpty()) { Text("Delete all sessions…") }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete all ${all.size} sessions?") },
        text = { Text("This permanently deletes every video, result and stat (${Format.bytes(bytes)}). It cannot be undone.") },
        confirmButton = { TextButton(onClick = { vm.deleteAll(); confirmDelete = false }) { Text("Delete everything") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun Section(title: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}
