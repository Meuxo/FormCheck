package com.meuxo.formcheck.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.util.Range
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.HighSpeedVideoSessionConfig
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.meuxo.formcheck.ui.components.PermissionGate
import java.io.File

/**
 * Records straight into app storage (cache, then moved into the session folder). Picks the
 * highest frame rate the camera supports: high-speed (120/240) for the jump test when
 * available, otherwise up to 60 fps.
 */
@Composable
fun CaptureScreen(
    type: SessionType,
    onEvent: (NewSessionEvent) -> Unit,
    extraParams: (SessionParams) -> SessionParams = { it },
    overlay: @Composable () -> Unit = {},
    vm: NewSessionViewModel = hiltViewModel()
) {
    PermissionGate(
        permissions = listOf(Manifest.permission.CAMERA),
        rationale = "FormCheck needs the camera to record your session. Audio is not required."
    ) {
        CaptureContent(type, onEvent, extraParams, overlay, vm)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CaptureContent(
    type: SessionType,
    onEvent: (NewSessionEvent) -> Unit,
    extraParams: (SessionParams) -> SessionParams,
    overlay: @Composable () -> Unit,
    vm: NewSessionViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var capture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var fps by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy by vm.busy.collectAsState()
    val checker = remember { com.meuxo.formcheck.live.SetupChecker(context) }
    val setup by checker.state.collectAsState()
    val analysisExec = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }

    LaunchedEffect(vm) { vm.events.collect { e -> if (e is NewSessionEvent.Failed) error = e.message else onEvent(e) } }

    DisposableEffect(type) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD,
                androidx.camera.video.FallbackStrategy.lowerQualityThan(Quality.FHD))).build()
            val vc = VideoCapture.withOutput(recorder)
            provider.unbindAll()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            checker.start()
            val analysis = androidx.camera.core.ImageAnalysis.Builder()
                .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                .also { it.setAnalyzer(analysisExec, checker) }
            val bound = (if (type == SessionType.JUMP) bindHighSpeed(provider, lifecycleOwner, selector, preview) else null)
                ?: bindNormal(provider, lifecycleOwner, selector, preview, vc, analysis)
            if (bound == null) error = "Could not open the camera." else { capture = bound.first; fps = bound.second }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            checker.stop(); analysisExec.shutdown()
            recording?.stop()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        overlay()
        Column(Modifier.align(Alignment.TopCenter).padding(16.dp).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(8.dp)) {
            Text(if (fps > 0) "Recording at $fps fps" else "Opening camera…", color = Color.White)
            if (fps in 1..35) Text("Only $fps fps is available: timing-based metrics will have lower confidence.", color = Color(0xFFFFD54F))
            error?.let { Text(it, color = Color(0xFFFF8A80)) }
            if (recording == null) {
                Text(type.filmingGuide().first(), color = Color.White, style = MaterialTheme.typography.bodySmall)
                SetupLine("Whole body in frame", setup.fullBody, setup.fullBodyHint ?: "Checked only in normal-speed mode")
                SetupLine("Lighting", setup.lighting, if (setup.lighting == false) "Too dark or too bright" else null)
                SetupLine("Phone steady", setup.steady, if (setup.steady == false) "Prop the phone up or use a tripod" else null)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FloatingActionButton(
                onClick = {
                    val vc = capture ?: return@FloatingActionButton
                    if (recording != null) { recording?.stop(); recording = null; return@FloatingActionButton }
                    val file = File(context.cacheDir, "rec_${System.currentTimeMillis()}.mp4")
                    recording = vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
                        .start(ContextCompat.getMainExecutor(context)) { ev ->
                            if (ev is VideoRecordEvent.Finalize) {
                                if (!ev.hasError() || file.length() > 0) vm.importRecording(type, file, extraParams)
                                else error = "Recording failed (code ${ev.error})."
                            }
                        }
                },
                shape = CircleShape, containerColor = if (recording != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ) {
                Icon(if (recording != null) Icons.Default.Stop else Icons.Default.Videocam,
                    contentDescription = if (recording != null) "Stop recording" else "Start recording", modifier = Modifier.size(32.dp))
            }
            Text(if (busy) "Saving…" else if (recording != null) "Recording… tap to stop" else "Tap to record", color = Color.White)
        }
    }
}

@Composable
private fun SetupLine(label: String, ok: Boolean?, hint: String?) {
    val (mark, color) = when (ok) { true -> "✓" to Color(0xFFA5D6A7); false -> "✗" to Color(0xFFFF8A80); null -> "…" to Color.LightGray }
    Text("$mark $label" + (if (ok != true && hint != null) ": $hint" else ""), color = color, style = MaterialTheme.typography.bodySmall)
}

/**
 * Normal session at the highest supported frame rate ≤ 60, with the setup checker if the
 * camera can run analysis alongside recording. Returns the capture and fps.
 */
private fun bindNormal(
    provider: ProcessCameraProvider, owner: androidx.lifecycle.LifecycleOwner, selector: CameraSelector,
    preview: Preview, vc: VideoCapture<Recorder>, analysis: androidx.camera.core.ImageAnalysis
): Pair<VideoCapture<Recorder>, Int>? = try {
    val info = provider.getCameraInfo(selector)
    fun build(withAnalysis: Boolean, range: Range<Int>?) =
        (if (withAnalysis) SessionConfig.Builder(preview, vc, analysis) else SessionConfig.Builder(preview, vc))
            .let { b -> if (range != null) b.setFrameRateRange(range) else b }.build()
    val ranges = runCatching { info.getSupportedFrameRateRanges(build(false, null)) }.getOrDefault(emptySet())
    val best = ranges.filter { it.upper <= 60 && it.lower == it.upper }.maxByOrNull { it.upper }
        ?: ranges.filter { it.upper <= 60 }.maxByOrNull { it.upper }
    val withAnalysis = build(true, best)
    val config = if (runCatching { info.isSessionConfigSupported(withAnalysis) }.getOrDefault(false)) withAnalysis else build(false, best)
    provider.bindToLifecycle(owner, selector, config)
    vc to (best?.upper ?: 30)
} catch (t: Throwable) {
    runCatching { provider.unbindAll(); provider.bindToLifecycle(owner, selector, preview, vc); vc to 30 }.getOrNull()
}

/** High-speed (≥ 120 fps) session for the jump test, if the device supports it. */
private fun bindHighSpeed(
    provider: ProcessCameraProvider, owner: androidx.lifecycle.LifecycleOwner, selector: CameraSelector, preview: Preview
): Pair<VideoCapture<Recorder>, Int>? = try {
    val info = provider.getCameraInfo(selector)
    val caps = Recorder.getHighSpeedVideoCapabilities(info)
    val quality = caps?.getSupportedQualities(androidx.camera.core.DynamicRange.SDR)?.firstOrNull()
    if (caps == null || quality == null) null else {
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build()
        val vc = VideoCapture.withOutput(recorder)
        val probe = HighSpeedVideoSessionConfig(vc, preview)
        val best: Range<Int>? = info.getSupportedFrameRateRanges(probe).maxByOrNull { it.upper }
        if (best == null) null else {
            // Slow motion off: the file keeps real-time timestamps, which flight time needs.
            val cfg = HighSpeedVideoSessionConfig.Builder(vc).setPreview(preview).setFrameRateRange(best).setSlowMotionEnabled(false).build()
            if (!info.isSessionConfigSupported(cfg)) null else {
                provider.bindToLifecycle(owner, selector, cfg)
                vc to best.upper
            }
        }
    }
} catch (_: Throwable) { null }
