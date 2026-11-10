package com.meuxo.formcheck.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meuxo.formcheck.data.settings.SettingsRepository
import com.meuxo.formcheck.data.storage.SessionFiles
import com.meuxo.formcheck.domain.model.CourtCalibration
import com.meuxo.formcheck.domain.model.CourtSize
import com.meuxo.formcheck.domain.model.CourtTap
import com.meuxo.formcheck.domain.model.PointNorm
import com.meuxo.formcheck.shootaround.CourtPoint
import com.meuxo.formcheck.shootaround.CourtSpec
import com.meuxo.formcheck.tracking.pipeline.PeopleDetector
import com.meuxo.formcheck.ui.components.CourtGuide
import com.meuxo.formcheck.work.AnalysisScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val files: SessionFiles,
    private val scheduler: AnalysisScheduler,
    settings: SettingsRepository
) : ViewModel() {
    val frame = MutableStateFlow<Bitmap?>(null)
    val defaultCourt = MutableStateFlow(CourtSize.HIGH_SCHOOL)
    init { viewModelScope.launch { settings.settings.collect { defaultCourt.value = it.courtSize } } }

    fun load(id: Long, fromFrame: Int) = viewModelScope.launch(Dispatchers.Default) {
        val timeUs = if (fromFrame > 0) files.readTrack(id)?.let { c -> c.mediaTimeUs(fromFrame.coerceAtMost(c.frames.size - 1)) } ?: 500_000 else 500_000
        frame.value = PeopleDetector.previewFrame(files.media(id), timeUs)
    }

    suspend fun save(id: Long, rim: Pair<PointNorm, PointNorm>?, court: CourtCalibration?, fromFrame: Int) = withContext(Dispatchers.IO) {
        val p = files.readParams(id) ?: return@withContext
        val keepRim = rim ?: (p.rimLeft?.let { l -> p.rimRight?.let { r -> l to r } })
        files.writeParams(id, p.copy(
            rimLeft = keepRim?.first, rimRight = keepRim?.second,
            court = (p.court.filter { it.fromFrame < (court?.fromFrame ?: Int.MAX_VALUE) } + listOfNotNull(court))
        ))
        // A recalibration only changes locations and rim, so the saved tracking data is reused.
        scheduler.enqueue(id, reuseTrack = fromFrame > 0 && files.track(id).exists())
    }
}

/**
 * Shootaround calibration (spec 6): rim edges, court size, then ≥ 4 known court points tapped on
 * a frozen frame. Everything is optional; skipped parts are reported as Not measured.
 */
@Composable
fun CalibrationScreen(id: Long, fromFrame: Int, onDone: () -> Unit, vm: CalibrationViewModel = hiltViewModel()) {
    LaunchedEffect(id, fromFrame) { vm.load(id, fromFrame) }
    val bmp by vm.frame.collectAsState()
    val defaultCourt by vm.defaultCourt.collectAsState()
    var court by remember { mutableStateOf<CourtSize?>(null) }
    val courtSize = court ?: defaultCourt
    val spec = CourtSpec.of(courtSize)
    val rimTaps = remember { mutableStateListOf<PointNorm>() }
    val taps = remember { mutableStateListOf<CourtTap>() }
    var pointIdx by remember { mutableIntStateOf(0) }
    var stage by remember { mutableIntStateOf(0) }  // 0 = rim, 1 = court
    val scope = rememberCoroutineScope()
    val points = CourtPoint.entries

    fun finish(withCourt: Boolean) = scope.launch {
        vm.save(id, if (rimTaps.size == 2) rimTaps[0] to rimTaps[1] else null,
            if (withCourt && taps.size >= 4) CourtCalibration(courtSize, taps.toList(), fromFrame) else null, fromFrame)
        onDone()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (fromFrame > 0) "Recalibrate" else "Calibrate the court", style = MaterialTheme.typography.headlineSmall)
        val b = bmp
        if (b == null) { CircularProgressIndicator(); return@Column }
        Text(if (stage == 0) {
            when (rimTaps.size) { 0 -> "Tap the LEFT edge of the rim."; 1 -> "Now tap the RIGHT edge of the rim."; else -> "Rim set." }
        } else if (pointIdx < points.size) "Tap: ${points[pointIdx].label}. Skip it if it isn't visible." else "All points done.")
        Box(Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)) {
            Image(b.asImageBitmap(), contentDescription = "Frame from your video", modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize().pointerInput(stage, pointIdx, rimTaps.size) {
                detectTapGestures { pos ->
                    val p = PointNorm((pos.x / size.width).toDouble(), (pos.y / size.height).toDouble())
                    if (stage == 0) { if (rimTaps.size < 2) rimTaps += p }
                    else if (pointIdx < points.size) { taps += CourtTap(p, points[pointIdx].name); pointIdx++ }
                }
            }) {
                rimTaps.forEach { drawCircle(Color(0xFFFF5252), 12f, Offset((it.x * size.width).toFloat(), (it.y * size.height).toFloat()), style = Stroke(4f)) }
                taps.forEach { drawCircle(Color(0xFFFFEB3B), 10f, Offset((it.image.x * size.width).toFloat(), (it.image.y * size.height).toFloat())) }
            }
        }
        if (stage == 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { stage = 1 }, enabled = rimTaps.size == 2) { Text("Next") }
                OutlinedButton(onClick = { rimTaps.clear() }) { Text("Redo") }
                TextButton(onClick = { rimTaps.clear(); stage = 1 }) { Text("Rim not visible") }
            }
            Text("Without the rim, makes and misses are marked Uncertain.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Court size", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CourtSize.entries.forEach { s -> FilterChip(courtSize == s, { court = s }, label = { Text(s.label) }) }
            }
            CourtGuide(spec, points.getOrNull(pointIdx)?.position(spec), taps.mapNotNull { t -> runCatching { CourtPoint.valueOf(t.courtPointId).position(spec) }.getOrNull() })
            Text("${taps.size} of at least 4 points.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (pointIdx < points.size) pointIdx++ }, enabled = pointIdx < points.size) { Text("Skip point") }
                OutlinedButton(onClick = { taps.clear(); pointIdx = 0 }) { Text("Redo") }
            }
            Button(onClick = { finish(true) }, enabled = taps.size >= 4, modifier = Modifier.fillMaxWidth()) { Text("Save and analyze") }
            TextButton(onClick = { finish(false) }, modifier = Modifier.fillMaxWidth()) {
                Text("Skip court calibration (shot locations will be Not measured)")
            }
        }
    }
}
