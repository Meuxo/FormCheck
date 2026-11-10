package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meuxo.formcheck.domain.model.SessionStatus
import com.meuxo.formcheck.domain.repository.SessionRepository
import com.meuxo.formcheck.work.AnalysisProgress
import com.meuxo.formcheck.work.AnalysisScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AnalysisProgressViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repo: SessionRepository,
    private val scheduler: AnalysisScheduler
) : ViewModel() {
    val id: Long = savedState.get<Long>("id") ?: -1
    val session = repo.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val progress = scheduler.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, AnalysisProgress(null, 0, null))
    fun retry() = scheduler.enqueue(id)
}

@Composable
fun AnalysisProgressScreen(onDone: (Long) -> Unit, onLeave: () -> Unit, vm: AnalysisProgressViewModel = hiltViewModel()) {
    val s by vm.session.collectAsState()
    val p by vm.progress.collectAsState()
    LaunchedEffect(s?.status) { if (s?.status == SessionStatus.DONE) onDone(vm.id) }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (s?.status) {
                SessionStatus.FAILED -> {
                    Text("Analysis failed", style = MaterialTheme.typography.titleLarge)
                    Text(s?.statusMessage ?: "Unknown error", textAlign = TextAlign.Center)
                    Button(onClick = vm::retry) { Text("Try again") }
                }
                else -> {
                    Text("Analyzing on this phone", style = MaterialTheme.typography.titleLarge)
                    LinearProgressIndicator(progress = { p.percent / 100f },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${p.percent} percent analyzed" })
                    Text("${p.percent}% · every frame at the video's own frame rate")
                    Text("You can leave this screen. The analysis keeps running and the session appears in your Library when it's done.",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = onLeave) { Text("Back to home") }
        }
    }
}
