package com.meuxo.formcheck.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.speech.tts.TextToSpeech
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meuxo.formcheck.domain.model.SessionParams
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.live.LiveEngine
import com.meuxo.formcheck.live.LiveLogic
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.tracking.TrackingConfig
import com.meuxo.formcheck.ui.components.OverlayToggles
import com.meuxo.formcheck.ui.components.PoseOverlay
import com.meuxo.formcheck.ui.components.PermissionGate
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Live mode: preview + live analysis overlay + recording, all at once. Optional spoken cues,
 * at most one every [TrackingConfig.VOICE_CUE_MIN_INTERVAL_SEC] seconds.
 */
@Composable
fun LiveCaptureScreen(
    type: SessionType,
    logic: () -> LiveLogic?,
    voiceCues: Boolean,
    onEvent: (NewSessionEvent) -> Unit,
    extraParams: (SessionParams) -> SessionParams = { it },
    vm: NewSessionViewModel = hiltViewModel()
) {
    PermissionGate(listOf(Manifest.permission.CAMERA), "FormCheck needs the camera for live mode. Nothing is uploaded.") {
        LiveContent(type, logic, voiceCues, onEvent, extraParams, vm)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun LiveContent(
    type: SessionType, logicFactory: () -> LiveLogic?, voiceCues: Boolean,
    onEvent: (NewSessionEvent) -> Unit, extraParams: (SessionParams) -> SessionParams, vm: NewSessionViewModel
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val engine = remember { LiveEngine(context, logicFactory(), trackBall = type != SessionType.JUMP) }
    val state by engine.state.collectAsState()
    var capture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var analysisBound by remember { mutableStateOf(true) }
    var lastCueAt by remember { mutableLongStateOf(0L) }
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }

    LaunchedEffect(vm) { vm.events.collect { onEvent(it) } }
    DisposableEffect(voiceCues) {
        if (voiceCues) tts.value = TextToSpeech(context) { ok -> if (ok == TextToSpeech.SUCCESS) tts.value?.language = Locale.getDefault() }
        onDispose { tts.value?.shutdown() }
    }
    LaunchedEffect(state.status.cue) {
        val cue = state.status.cue ?: return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (voiceCues && now - lastCueAt > TrackingConfig.VOICE_CUE_MIN_INTERVAL_SEC * 1000) {
            lastCueAt = now
            tts.value?.speak(cue, TextToSpeech.QUEUE_FLUSH, null, "cue")
        }
    }

    DisposableEffect(Unit) {
        val exec = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
            val vc = VideoCapture.withOutput(recorder)
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(exec, engine) }
            provider.unbindAll()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.bindToLifecycle(owner, selector, SessionConfig.Builder(preview, vc, analysis).build())
            } catch (_: Throwable) {
                // Not every camera can stream preview, analysis and recording at once: record only.
                analysisBound = false
                runCatching { provider.unbindAll(); provider.bindToLifecycle(owner, selector, preview, vc) }
            }
            capture = vc
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            recording?.stop(); engine.stop(); exec.shutdown()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        state.frame?.let { f ->
            if (!state.recordOnly && state.width > 0) {
                val single = TrackedClip(state.width, state.height, listOf(com.meuxo.formcheck.tracking.TrackedFrame(0, f.timeUs, f.pose, f.ball)), state.rim)
                PoseOverlay(single, 0, OverlayToggles(ballTrail = false), Modifier.fillMaxSize())
            }
        }
        Column(Modifier.align(Alignment.TopCenter).padding(16.dp).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                !analysisBound -> Text("This camera can't analyze while recording. Recording now; the video is analyzed afterwards.", color = Color(0xFFFFD54F))
                state.recordOnly -> Text("Your phone can't keep up live (${state.processedFps.toInt()} fps). Recording now; full analysis runs afterwards.", color = Color(0xFFFFD54F))
                else -> {
                    Text("Live ${state.processedFps.toInt()} fps", color = Color.White)
                    state.status.lines.forEach { Text(it, color = Color.White) }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FloatingActionButton(onClick = {
                val vc = capture ?: return@FloatingActionButton
                if (recording != null) { recording?.stop(); recording = null; return@FloatingActionButton }
                val file = File(context.cacheDir, "live_${System.currentTimeMillis()}.mp4")
                recording = vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
                    .start(ContextCompat.getMainExecutor(context)) { ev ->
                        if (ev is VideoRecordEvent.Finalize && file.length() > 0) {
                            engine.stop()
                            vm.importRecording(type, file) { extraParams(it).copy(recordedLive = true) }
                        }
                    }
            }, shape = CircleShape, containerColor = if (recording != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) {
                Icon(if (recording != null) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = if (recording != null) "Stop session" else "Start session", modifier = Modifier.size(32.dp))
            }
            Text(if (recording != null) "Session running · tap to finish" else "Tap to start", color = Color.White)
        }
    }
}
