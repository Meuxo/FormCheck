package com.meuxo.formcheck.ui.screens

import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.export.AnnotatedVideoExporter
import com.meuxo.formcheck.export.FrameLookup
import com.meuxo.formcheck.ui.components.Format
import com.meuxo.formcheck.ui.components.OverlayToggles
import com.meuxo.formcheck.ui.components.PoseOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays the saved video and redraws the overlay live from saved landmarks (spec 5):
 * skeleton / ball trail / angle labels toggles, 0.25× and 0.5× slow motion, frame stepping.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackScreen(
    startFrame: Int?,
    metricName: String?,
    shotIndex: Int?,
    onBack: () -> Unit,
    vm: SessionDataViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadClip() }
    val clip by vm.clip.collectAsState()
    val result by vm.result.collectAsState()
    val settings by vm.settings.collectAsState()
    val error by vm.error.collectAsState()
    var toggles by remember { mutableStateOf(OverlayToggles()) }
    var speed by remember { mutableFloatStateOf(1f) }
    var frame by remember { mutableIntStateOf(startFrame ?: 0) }
    var playing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val metricId = metricName?.let { n -> runCatching { MetricId.valueOf(n) }.getOrNull() }
    val highlight = result?.let { r ->
        val pool = if (shotIndex != null) r.shots.getOrNull(shotIndex)?.metrics.orEmpty() + r.metrics
        else r.shots.flatMap { it.metrics } + r.metrics
        pool.firstOrNull { it.id == metricId }
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(vm.media)))
            setSeekParameters(SeekParameters.EXACT)
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(clip) {
        val c = clip ?: return@LaunchedEffect
        if (startFrame != null && startFrame in c.frames.indices) player.seekTo(c.mediaTimeUs(startFrame) / 1000)
    }
    LaunchedEffect(player, clip) {
        val c = clip ?: return@LaunchedEffect
        while (true) {
            if (player.isPlaying) frame = FrameLookup.indexForMediaTime(c, player.currentPosition * 1000)
            playing = player.isPlaying
            delay(16)
        }
    }

    fun step(d: Int) {
        val c = clip ?: return
        player.pause()
        frame = (frame + d).coerceIn(0, c.frames.size - 1)
        player.seekTo(c.mediaTimeUs(frame) / 1000)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(highlight?.id?.displayName ?: "Playback") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }, actions = {
            IconButton(enabled = clip != null && !exporting, onClick = {
                val c = clip ?: return@IconButton
                exporting = true; exportMsg = "Exporting annotated video…"
                scope.launch {
                    try {
                        val out = java.io.File(vm.media.parentFile, "annotated.mp4")
                        AnnotatedVideoExporter(context).export(vm.media, c, out)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", out)
                        exportMsg = null
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share annotated video"))
                    } catch (t: Throwable) { exportMsg = "Export failed: ${t.message}" } finally { exporting = false }
                }
            }) { Icon(Icons.Default.Share, contentDescription = "Export and share annotated video") }
        })
    }) { padding ->
        val c = clip
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    if (error != null) Text(error!!) else CircularProgressIndicator()
                }
                return@Column
            }
            Box(Modifier.fillMaxWidth().aspectRatio(c.width.toFloat() / c.height)) {
                AndroidView(factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player; useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                }, modifier = Modifier.fillMaxSize())
                PoseOverlay(c, frame, toggles, highlight = highlight, arcs = result?.shots?.mapNotNull { it.arc }.orEmpty())
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val t = c.frames.getOrNull(frame)?.let { (it.timeUs - c.frames[0].timeUs) / 1000 } ?: 0
                Text("Frame ${frame + 1} of ${c.frames.size} · ${t} ms · ${c.fps.toInt()} fps", style = MaterialTheme.typography.bodySmall)
                Slider(value = frame.toFloat(), onValueChange = { v -> frame = v.toInt(); player.pause(); player.seekTo(c.mediaTimeUs(frame) / 1000) },
                    valueRange = 0f..(c.frames.size - 1).coerceAtLeast(1).toFloat())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { step(-1) }) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous frame") }
                    IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
                    }
                    IconButton(onClick = { step(1) }) { Icon(Icons.Default.SkipNext, contentDescription = "Next frame") }
                    listOf(0.25f, 0.5f, 1f).forEach { sp ->
                        FilterChip(speed == sp, { speed = sp; player.playbackParameters = PlaybackParameters(sp) },
                            label = { Text(if (sp == 1f) "1×" else "$sp×") }, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(toggles.skeleton, { toggles = toggles.copy(skeleton = !toggles.skeleton) }, label = { Text("Skeleton") })
                    FilterChip(toggles.ballTrail, { toggles = toggles.copy(ballTrail = !toggles.ballTrail) }, label = { Text("Ball trail") })
                    FilterChip(toggles.angleLabels, { toggles = toggles.copy(angleLabels = !toggles.angleLabels) }, label = { Text("Angle labels") })
                }
                highlight?.let { m ->
                    Text("${m.id.displayName}: ${Format.value(m, settings.units)}", style = MaterialTheme.typography.titleMedium)
                    m.evidence?.let { e ->
                        Text("Measured on frame ${e.keyFrame + 1} (from frames ${e.frameStart + 1}–${e.frameEnd + 1}).", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(false, { frame = e.frameStart; step(0) }, label = { Text("Start") })
                            FilterChip(false, { frame = e.keyFrame; step(0) }, label = { Text("Key frame") })
                            FilterChip(false, { frame = e.frameEnd; step(0) }, label = { Text("End") })
                        }
                    }
                }
                exportMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
