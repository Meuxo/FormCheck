package com.meuxo.formcheck.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meuxo.formcheck.data.settings.SettingsRepository
import com.meuxo.formcheck.data.storage.SessionFiles
import com.meuxo.formcheck.domain.model.Session
import com.meuxo.formcheck.domain.model.SessionResult
import com.meuxo.formcheck.domain.model.ShotOutcome
import com.meuxo.formcheck.domain.model.UserSettings
import com.meuxo.formcheck.domain.repository.SessionRepository
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.work.AnalysisScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Loads one session's row, result, tracking data and media for Results and Playback. */
@HiltViewModel
class SessionDataViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: SessionRepository,
    private val files: SessionFiles,
    private val scheduler: AnalysisScheduler,
    settings: SettingsRepository
) : ViewModel() {
    val id: Long = savedState.get<Long>("id") ?: -1
    val session: StateFlow<Session?> = repo.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings: StateFlow<UserSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    private val _result = MutableStateFlow<SessionResult?>(null)
    val result = _result.asStateFlow()
    private val _clip = MutableStateFlow<TrackedClip?>(null)
    val clip = _clip.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val media: File get() = files.media(id)

    private val _params = MutableStateFlow<com.meuxo.formcheck.domain.model.SessionParams?>(null)
    val params = _params.asStateFlow()

    init { reload() }

    fun reload() = viewModelScope.launch {
        try {
            _result.value = files.readResult(id)
            _params.value = files.readParams(id)
        } catch (t: Throwable) { _error.value = "Could not read results: ${t.message}" }
    }

    fun loadClip() = viewModelScope.launch {
        if (_clip.value != null) return@launch
        try { _clip.value = files.readTrack(id) } catch (t: Throwable) { _error.value = "Could not read tracking data: ${t.message}" }
    }

    fun reanalyze() = scheduler.enqueue(id)

    fun history(metric: com.meuxo.formcheck.domain.model.MetricId) = repo.observeMetricHistory(metric.name)

    fun correctOutcome(shotIndex: Int, outcome: ShotOutcome) = viewModelScope.launch {
        repo.correctOutcome(id, shotIndex, outcome); reload()
    }
}
