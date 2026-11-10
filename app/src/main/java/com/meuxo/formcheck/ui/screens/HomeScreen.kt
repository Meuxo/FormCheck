package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Height
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meuxo.formcheck.domain.model.Session
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(repository: SessionRepository, settings: com.meuxo.formcheck.data.settings.SettingsRepository) : ViewModel() {
    val recent: StateFlow<List<Session>> = repository.observeAll()
        .map { it.take(5) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val progress = kotlinx.coroutines.flow.combine(repository.observeAllMeasuredMetrics(), repository.observeAll(), settings.settings) { r, s, st -> ProgressData(r, s, st) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

/** Modes wired up in this build. */
val AVAILABLE_MODES = SessionType.entries.toSet()

fun SessionType.icon(): ImageVector = when (this) {
    SessionType.FORM -> Icons.Default.SportsBasketball
    SessionType.SHOOTAROUND -> Icons.Default.Insights
    SessionType.DRIBBLE -> Icons.Default.DirectionsRun
    SessionType.JUMP -> Icons.Default.Height
}

fun SessionType.blurb(): String = when (this) {
    SessionType.FORM -> "Film one shot. Get measured joint angles, release, arc and balance."
    SessionType.SHOOTAROUND -> "Tripod at the hoop. Counts makes and misses, shot chart by zone."
    SessionType.DRIBBLE -> "Pick a drill. Measures stance, dribble height, rhythm and eyes up."
    SessionType.JUMP -> "Vertical jump from flight time, cross-checked with hip rise."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMode: (SessionType) -> Unit,
    onOpenSession: (Session) -> Unit,
    onLibrary: () -> Unit,
    onProgress: () -> Unit,
    onTutorial: () -> Unit,
    onSettings: () -> Unit,
    vm: HomeViewModel = hiltViewModel()
) {
    val recent by vm.recent.collectAsState()
    val progress by vm.progress.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("FormCheck", style = MaterialTheme.typography.headlineMedium) },
            actions = {
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            }
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(SessionType.entries) { t ->
                val enabled = t in AVAILABLE_MODES
                Card(
                    onClick = { onMode(t) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.icon(), contentDescription = null, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.size(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(if (enabled) t.blurb() else "Not available in this build yet.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onLibrary) { Icon(Icons.Default.VideoLibrary, null); Spacer(Modifier.size(6.dp)); Text("Library") }
                    TextButton(onClick = onProgress) { Icon(Icons.Default.Timeline, null); Spacer(Modifier.size(6.dp)); Text("Progress") }
                    TextButton(onClick = onTutorial) { Icon(Icons.Default.School, null); Spacer(Modifier.size(6.dp)); Text("How to film") }
                }
            }
            progress?.takeIf { it.sessions.isNotEmpty() }?.let { p -> item { WeeklyCard(p) } }
            if (recent.isNotEmpty()) {
                item { Text("Recent", style = MaterialTheme.typography.titleMedium) }
                items(recent, key = { it.id }) { s -> SessionRow(s, onClick = { onOpenSession(s) }) }
            }
        }
    }
}
