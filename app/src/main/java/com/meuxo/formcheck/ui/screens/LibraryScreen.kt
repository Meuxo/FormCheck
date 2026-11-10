package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.meuxo.formcheck.domain.model.Session
import com.meuxo.formcheck.domain.model.SessionStatus
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.domain.repository.SessionRepository
import com.meuxo.formcheck.ui.components.Format
import com.meuxo.formcheck.work.AnalysisScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: SessionRepository,
    private val scheduler: AnalysisScheduler
) : ViewModel() {
    val filter = MutableStateFlow<SessionType?>(null)
    val sessions = filter.flatMapLatest { repo.observeAll(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val totalBytes = repo.observeTotalBytes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun rename(id: Long, title: String) = viewModelScope.launch { repo.rename(id, title) }
    fun delete(id: Long) = viewModelScope.launch { scheduler.cancel(id); repo.delete(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: () -> Unit, onOpen: (Session) -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val sessions by vm.sessions.collectAsState()
    val filter by vm.filter.collectAsState()
    val bytes by vm.totalBytes.collectAsState()
    var renaming by remember { mutableStateOf<Session?>(null) }
    var deleting by remember { mutableStateOf<Session?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Library") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item {
                Text("${sessions.size} sessions · ${Format.bytes(bytes)} used on this phone", style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == null, { vm.filter.value = null }, label = { Text("All") })
                    SessionType.entries.forEach { t -> FilterChip(filter == t, { vm.filter.value = t }, label = { Text(t.label) }) }
                }
            }
            if (sessions.isEmpty()) item { Text("No sessions yet.", Modifier.padding(vertical = 24.dp)) }
            items(sessions, key = { it.id }) { s ->
                SessionRow(s, onClick = { onOpen(s) }, onRename = { renaming = s }, onDelete = { deleting = s })
            }
        }
    }

    renaming?.let { s ->
        var text by remember(s.id) { mutableStateOf(s.title ?: "") }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename session") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { vm.rename(s.id, text); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { s ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete session?") },
            text = { Text("This permanently deletes \"${s.displayTitle}\" from ${Format.date(s.timestamp)}, its video and its results" +
                (s.sizeBytes?.let { " (${Format.bytes(it)})" } ?: "") + ". This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.delete(s.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
fun SessionRow(s: Session, onClick: () -> Unit, onRename: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center) {
                if (s.thumbPath != null) AsyncImage(File(s.thumbPath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(s.type.icon(), contentDescription = null)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.displayTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${s.type.label} · ${Format.date(s.timestamp)}", style = MaterialTheme.typography.bodySmall)
                Text(summary(s), style = MaterialTheme.typography.bodySmall)
            }
            onRename?.let { IconButton(onClick = it) { Icon(Icons.Default.Edit, contentDescription = "Rename ${s.displayTitle}") } }
            onDelete?.let { IconButton(onClick = it) { Icon(Icons.Default.Delete, contentDescription = "Delete ${s.displayTitle}") } }
        }
    }
}

private fun summary(s: Session): String = when (s.status) {
    SessionStatus.DONE -> buildString {
        if (s.attempts != null && s.makes != null) append("${s.makes}/${s.attempts} made · ")
        append(if (s.overallScore != null) "Score ${s.overallScore}% (${s.includedMetrics} of ${s.totalMetrics} metrics)" else "No scored metrics measured")
    }
    SessionStatus.LEGACY -> "v1 session · scores not shown"
    SessionStatus.FAILED -> "Analysis failed: ${s.statusMessage ?: "unknown error"}"
    SessionStatus.ANALYZING -> "Analyzing…"
    SessionStatus.PENDING, SessionStatus.IMPORTING -> s.statusMessage ?: "Waiting to analyze"
}
