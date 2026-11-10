package com.meuxo.formcheck.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meuxo.formcheck.data.storage.SessionFiles
import com.meuxo.formcheck.domain.model.PointNorm
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.tracking.pipeline.PersonBox
import com.meuxo.formcheck.work.AnalysisScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PersonPickViewModel @Inject constructor(
    private val files: SessionFiles,
    private val scheduler: AnalysisScheduler,
    private val peopleDetector: com.meuxo.formcheck.tracking.pipeline.PeopleDetector
) : ViewModel() {
    val frame = MutableStateFlow<Bitmap?>(null)
    val people = MutableStateFlow<List<PersonBox>>(emptyList())

    fun load(id: Long) = viewModelScope.launch(Dispatchers.Default) {
        val f = files.media(id)
        frame.value = com.meuxo.formcheck.tracking.pipeline.PeopleDetector.previewFrame(f)
        people.value = peopleDetector.detect(f)
    }

    /** Saves the chosen shooter and returns the session type so the caller can route. */
    suspend fun choose(id: Long, p: PersonBox): SessionType = withContext(Dispatchers.IO) {
        val params = files.readParams(id)!!.copy(shooterSeed = PointNorm(p.hipX, p.hipY))
        files.writeParams(id, params)
        if (params.type != SessionType.SHOOTAROUND) scheduler.enqueue(id)
        params.type
    }
}

@Composable
fun PersonPickScreen(id: Long, onChosen: (SessionType) -> Unit, vm: PersonPickViewModel = hiltViewModel()) {
    LaunchedEffect(id) { vm.load(id) }
    val frame by vm.frame.collectAsState()
    val people by vm.people.collectAsState()
    var chosenJob by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Who is shooting?", style = MaterialTheme.typography.headlineSmall)
        Text("More than one person is in the video. Tap the person to analyze; the app follows them through the clip.")
        val bmp = frame
        if (bmp == null) { CircularProgressIndicator(); return@Column }
        Box(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height)) {
            Image(bmp.asImageBitmap(), contentDescription = "First frame of the video", modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize().pointerInput(people) {
                detectTapGestures { pos ->
                    if (chosenJob) return@detectTapGestures
                    val nx = pos.x / size.width; val ny = pos.y / size.height
                    val hit = people.minByOrNull { p ->
                        val inside = nx in p.left..p.right && ny in p.top..p.bottom
                        (if (inside) 0.0 else 10.0) + kotlin.math.hypot(nx - p.hipX, ny - p.hipY)
                    } ?: return@detectTapGestures
                    chosenJob = true
                    scope.launch { onChosen(vm.choose(id, hit)) }
                }
            }) {
                people.forEachIndexed { i, p ->
                    drawRect(Color(0xFF4FC3F7), Offset((p.left * size.width).toFloat(), (p.top * size.height).toFloat()),
                        Size(((p.right - p.left) * size.width).toFloat(), ((p.bottom - p.top) * size.height).toFloat()), style = Stroke(5f))
                }
            }
        }
        Text("${people.size} people found.", style = MaterialTheme.typography.bodySmall)
    }
}
