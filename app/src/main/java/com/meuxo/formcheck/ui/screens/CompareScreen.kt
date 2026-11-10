package com.meuxo.formcheck.ui.screens

import androidx.annotation.OptIn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.meuxo.formcheck.domain.model.ShotResult
import com.meuxo.formcheck.export.FrameLookup
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.ui.components.Format
import com.meuxo.formcheck.ui.components.OverlayToggles
import com.meuxo.formcheck.ui.components.PoseOverlay
import kotlinx.coroutines.delay
import java.io.File

/** Best vs worst shot of a session (by per-shot overall score), each looping with its overlay. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(onBack: () -> Unit, vm: SessionDataViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.loadClip() }
    val clip by vm.clip.collectAsState()
    val result by vm.result.collectAsState()
    val settings by vm.settings.collectAsState()
    var speed by remember { mutableFloatStateOf(0.5f) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Best vs worst shot") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        val c = clip; val r = result
        if (c == null || r == null) { CircularProgressIndicator(Modifier.padding(padding).padding(32.dp)); return@Scaffold }
        val scored = r.shots.filter { it.overall?.percent != null }
        if (scored.size < 2) {
            Text("Needs at least two shots with a score.", Modifier.padding(padding).padding(24.dp)); return@Scaffold
        }
        val best = scored.maxBy { it.overall!!.percent!! }; val worst = scored.minBy { it.overall!!.percent!! }
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.25f, 0.5f, 1f).forEach { sp -> FilterChip(speed == sp, { speed = sp }, label = { Text("$sp×") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { ShotLoop(vm.media, c, best, speed, "Best: shot ${best.index + 1} · ${best.overall!!.percent}%") }
                Column(Modifier.weight(1f)) { ShotLoop(vm.media, c, worst, speed, "Worst: shot ${worst.index + 1} · ${worst.overall!!.percent}%") }
            }
            Text("Biggest differences", style = MaterialTheme.typography.titleMedium)
            best.metrics.filter { it.value != null }.mapNotNull { b ->
                worst.metrics.firstOrNull { it.id == b.id && it.value != null }?.let { w -> Triple(b, w, (b.score ?: 0) - (w.score ?: 0)) }
            }.sortedByDescending { kotlin.math.abs(it.third) }.take(5).forEach { (b, w, _) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(b.id.displayName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text("${Format.value(b, settings.units)} vs ${Format.value(w, settings.units)}")
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ShotLoop(media: File, clip: TrackedClip, shot: ShotResult, speed: Float, label: String) {
    val context = LocalContext.current
    val startMs = clip.mediaTimeUs((shot.startFrame - 10).coerceAtLeast(0)) / 1000
    val endMs = clip.mediaTimeUs((shot.endFrame + 10).coerceAtMost(clip.frames.size - 1)) / 1000
    val player = remember(shot.index) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.Builder().setUri(android.net.Uri.fromFile(media))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build())
            repeatMode = ExoPlayer.REPEAT_MODE_ONE; volume = 0f; prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(speed) { player.playbackParameters = PlaybackParameters(speed) }
    var frame by remember { mutableIntStateOf(shot.startFrame) }
    LaunchedEffect(player) { while (true) { frame = FrameLookup.indexForMediaTime(clip, (startMs + player.currentPosition) * 1000); delay(16) } }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Box(Modifier.fillMaxWidth().aspectRatio(clip.width.toFloat() / clip.height)) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } }, modifier = Modifier.fillMaxSize())
        PoseOverlay(clip, frame, OverlayToggles(angleLabels = false), arcs = listOfNotNull(shot.arc))
    }
}

