package com.meuxo.formcheck.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meuxo.formcheck.data.settings.SettingsRepository
import com.meuxo.formcheck.data.storage.SessionFiles
import com.meuxo.formcheck.domain.model.SessionParams
import com.meuxo.formcheck.domain.model.SessionStatus
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.domain.repository.SessionRepository
import com.meuxo.formcheck.tracking.pipeline.PeopleDetector
import com.meuxo.formcheck.work.AnalysisScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

sealed interface NewSessionEvent {
    data class PickPerson(val id: Long) : NewSessionEvent
    data class Calibrate(val id: Long) : NewSessionEvent
    data class Analyzing(val id: Long) : NewSessionEvent
    data class Failed(val message: String) : NewSessionEvent
}

/**
 * Creates a session: copies media into app storage, then routes to person selection,
 * court calibration (shootaround), or straight to analysis.
 */
@HiltViewModel
class NewSessionViewModel @Inject constructor(
    private val repo: SessionRepository,
    private val files: SessionFiles,
    private val settings: SettingsRepository,
    private val scheduler: AnalysisScheduler,
    private val peopleDetector: PeopleDetector
) : ViewModel() {

    private val _events = MutableSharedFlow<NewSessionEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun importUri(type: SessionType, uri: Uri, extra: (SessionParams) -> SessionParams = { it }) =
        start(type, extra) { id -> files.importFromUri(id, uri) }

    fun importRecording(type: SessionType, file: File, extra: (SessionParams) -> SessionParams = { it }) =
        start(type, extra) { id -> files.importRecording(id, file) }

    private fun start(type: SessionType, extra: (SessionParams) -> SessionParams, copy: suspend (Long) -> Long) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            var id = -1L
            try {
                val s = settings.current()
                val params = extra(SessionParams(type = type, shootingHand = s.shootingHand, heightM = s.heightCm?.div(100.0),
                    ballSize = s.ballSize, standingReachCm = s.standingReachCm))
                id = repo.create(params)
                val bytes = copy(id)
                repo.setMedia(id, bytes, durationMs(files.media(id)))
                val people = withContext(Dispatchers.Default) { peopleDetector.detect(files.media(id)) }
                _events.emit(
                    when {
                        people.size > 1 -> NewSessionEvent.PickPerson(id)
                        type == SessionType.SHOOTAROUND -> NewSessionEvent.Calibrate(id)
                        else -> { scheduler.enqueue(id); NewSessionEvent.Analyzing(id) }
                    }
                )
            } catch (t: Throwable) {
                if (id >= 0) repo.setStatus(id, SessionStatus.FAILED, t.message)
                _events.emit(NewSessionEvent.Failed(t.message ?: "Could not import the video"))
            } finally {
                _busy.value = false
            }
        }
    }

    private fun durationMs(f: File): Long? = runCatching {
        MediaMetadataRetriever().run {
            try { setDataSource(f.absolutePath); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { release() }
        }
    }.getOrNull()

}
