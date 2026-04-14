package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.meuxo.formcheck.domain.model.AnalysisResult
import com.meuxo.formcheck.domain.usecase.AnalyzeShotUseCase
import com.meuxo.formcheck.domain.usecase.SaveSessionUseCase
import com.meuxo.formcheck.ui.components.SegmentColors
import com.meuxo.formcheck.ui.components.SkeletonOverlay
import com.meuxo.formcheck.ui.components.colorForScore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AnalysisUiState {
    data object Idle : AnalysisUiState
    data object Running : AnalysisUiState
    data class Analyzed(val result: AnalysisResult) : AnalysisUiState
    data class Saved(val sessionId: Long) : AnalysisUiState
    data class Error(val message: String) : AnalysisUiState
}

@HiltViewModel
class AnalysisViewModel @Inject constructor(
    private val analyzeShot: AnalyzeShotUseCase,
    private val saveSession: SaveSessionUseCase,
    @Suppress("unused") savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow<AnalysisUiState>(AnalysisUiState.Idle)
    val state: StateFlow<AnalysisUiState> = _state.asStateFlow()

    fun analyze(mediaUri: String) {
        if (_state.value is AnalysisUiState.Running) return
        _state.value = AnalysisUiState.Running
        viewModelScope.launch {
            try {
                val result = analyzeShot(mediaUri)
                _state.value = AnalysisUiState.Analyzed(result)
            } catch (t: Throwable) {
                _state.value = AnalysisUiState.Error(t.message ?: "Analysis failed")
            }
        }
    }

    fun saveAndContinue() {
        val current = _state.value
        if (current !is AnalysisUiState.Analyzed) return
        viewModelScope.launch {
            try {
                val id = saveSession(current.result)
                _state.value = AnalysisUiState.Saved(id)
            } catch (t: Throwable) {
                _state.value = AnalysisUiState.Error(t.message ?: "Save failed")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    mediaUri: String,
    onComplete: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: AnalysisViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(mediaUri) { viewModel.analyze(mediaUri) }
    LaunchedEffect(state) {
        (state as? AnalysisUiState.Saved)?.let { onComplete(it.sessionId) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analyze Shot") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when (val s = state) {
                AnalysisUiState.Idle, AnalysisUiState.Running -> LoadingState()
                is AnalysisUiState.Error -> ErrorState(
                    message = s.message,
                    onRetry = { viewModel.analyze(mediaUri) }
                )
                is AnalysisUiState.Analyzed -> AnalyzedContent(
                    mediaUri = mediaUri,
                    result = s.result,
                    onSave = { viewModel.saveAndContinue() }
                )
                is AnalysisUiState.Saved -> Text("Opening results…")
            }
        }
    }
}

private val loadingTips = listOf(
    "Tip: Film from a 45° angle on your shooting side for the best results.",
    "Tip: Keep your full body in frame — head to feet.",
    "Tip: A good follow-through means your wrist is relaxed, fingers pointing down.",
    "Tip: Your power comes from your legs, not your arms.",
    "Tip: The ball should roll off your index and middle fingers last.",
    "Tip: A higher arc gives the ball a bigger window to go through the hoop.",
    "Tip: Your guide hand should stay still at release — it doesn't push the ball.",
    "Tip: Hold your follow-through until the ball hits the rim.",
    "Tip: A consistent shot pocket builds muscle memory faster.",
    "Tip: Your elbow should point at the rim, not flare out to the side."
)

@Composable
private fun LoadingState() {
    var tipIndex by remember { mutableIntStateOf((System.currentTimeMillis() % loadingTips.size).toInt()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            tipIndex = (tipIndex + 1) % loadingTips.size
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 32.dp)
    ) {
        CircularProgressIndicator()
        Text(
            "Detecting pose & evaluating form…",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            loadingTips[tipIndex],
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text("Could not analyze: $message")
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun AnalyzedContent(
    mediaUri: String,
    result: AnalysisResult,
    onSave: () -> Unit
) {
    val segmentColors = remember(result) { SegmentColors.fromResult(result) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Overall: ${result.overallScore}",
            style = MaterialTheme.typography.headlineMedium,
            color = colorForScore(result.overallScore)
        )

        if (result.isVideo) {
            VideoWithOverlay(
                mediaUri = mediaUri,
                result = result,
                segmentColors = segmentColors,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            ImageWithOverlay(
                mediaUri = mediaUri,
                result = result,
                segmentColors = segmentColors,
                modifier = Modifier.fillMaxWidth()
            )
        }

        ColorLegend(segmentColors)

        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Save & View Detailed Results")
        }
    }
}

/** Lightweight color key so the user knows what each tinted bone means. */
@Composable
private fun ColorLegend(colors: SegmentColors) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        LegendRow("Shooting arm", colors.rightArm)
        LegendRow("Guide hand", colors.leftArm)
        LegendRow("Torso / balance", colors.torso)
        LegendRow("Legs / knee bend", colors.rightLeg)
    }
}

@Composable
private fun LegendRow(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = color,
            shape = CircleShape,
            modifier = Modifier.size(14.dp)
        ) { }
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * ExoPlayer + skeleton overlay. Polls the player position on a 50 ms loop and
 * picks the closest pose-detected frame so the bones track the action.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoWithOverlay(
    mediaUri: String,
    result: AnalysisResult,
    segmentColors: SegmentColors,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exoPlayer = remember(mediaUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(mediaUri))
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(exoPlayer) {
        while (true) {
            positionMs = exoPlayer.currentPosition
            delay(50)
        }
    }

    val interval = result.frameIntervalMs.coerceAtLeast(1L)
    val frameIdx = ((positionMs / interval).toInt())
        .coerceIn(0, (result.landmarksByFrame.size - 1).coerceAtLeast(0))
    val currentLandmarks = result.landmarksByFrame.getOrNull(frameIdx)

    val ratio = if (result.sourceWidth > 0 && result.sourceHeight > 0)
        result.sourceWidth.toFloat() / result.sourceHeight.toFloat()
    else 16f / 9f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(16.dp))
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        SkeletonOverlay(
            landmarks = currentLandmarks,
            imageWidth = result.sourceWidth,
            imageHeight = result.sourceHeight,
            segmentColors = segmentColors,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun ImageWithOverlay(
    mediaUri: String,
    result: AnalysisResult,
    segmentColors: SegmentColors,
    modifier: Modifier = Modifier
) {
    val ratio = if (result.sourceWidth > 0 && result.sourceHeight > 0)
        result.sourceWidth.toFloat() / result.sourceHeight.toFloat()
    else 1f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(16.dp))
    ) {
        AsyncImage(
            model = mediaUri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
        SkeletonOverlay(
            landmarks = result.landmarksByFrame.firstOrNull(),
            imageWidth = result.sourceWidth,
            imageHeight = result.sourceHeight,
            segmentColors = segmentColors,
            modifier = Modifier.fillMaxSize()
        )
    }
}
