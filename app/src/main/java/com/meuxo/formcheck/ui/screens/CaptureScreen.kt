package com.meuxo.formcheck.ui.screens

import android.Manifest
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker

@Composable
fun CaptureScreen(
    onCaptured: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            PermissionChecker.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PermissionChecker.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result[Manifest.permission.CAMERA] == true
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission is required to record a shot.")
        }
        return
    }

    val previewView = remember { PreviewView(context) }
    var videoCapture by remember {
        mutableStateOf<VideoCapture<Recorder>?>(null)
    }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            val capture = VideoCapture.withOutput(recorder)
            videoCapture = capture
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
            } catch (_: Throwable) { }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            recording?.stop()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    val capture = videoCapture ?: return@FloatingActionButton
                    if (isRecording) {
                        recording?.stop()
                        recording = null
                        isRecording = false
                    } else {
                        val name = "FormCheck_${System.currentTimeMillis()}.mp4"
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, name)
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FormCheck")
                            }
                        }
                        val output = MediaStoreOutputOptions.Builder(
                            context.contentResolver,
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        ).setContentValues(values).build()

                        recording = capture.output
                            .prepareRecording(context, output)
                            .apply {
                                if (PermissionChecker.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PermissionChecker.PERMISSION_GRANTED) {
                                    withAudioEnabled()
                                }
                            }
                            .start(ContextCompat.getMainExecutor(context)) { event ->
                                if (event is VideoRecordEvent.Finalize && !event.hasError()) {
                                    onCaptured(event.outputResults.outputUri.toString())
                                }
                            }
                        isRecording = true
                    }
                },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop
                    else Icons.Default.Videocam,
                    contentDescription = if (isRecording) "Stop" else "Record",
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(
                text = if (isRecording) "Recording…" else "Tap to record (1–10s)",
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
