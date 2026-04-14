package com.meuxo.formcheck.ui.screens

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meuxo.formcheck.domain.model.Session
import com.meuxo.formcheck.domain.repository.SessionRepository
import com.meuxo.formcheck.ui.components.PhaseCard
import com.meuxo.formcheck.ui.components.colorForScore
import com.meuxo.formcheck.ui.components.scoreColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ResultsViewModel @Inject constructor(
    private val repository: SessionRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val sessionId =
        MutableStateFlow(savedStateHandle.get<Long>("sessionId") ?: 0L)
    val session: StateFlow<Session?> = sessionId
        .flatMapLatest { repository.getSession(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    sessionId: Long,
    onBack: () -> Unit,
    viewModel: ResultsViewModel = hiltViewModel()
) {
    val session by viewModel.session.collectAsState()
    var compareIdeal by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Results") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    val s = session
                    if (s != null) {
                        IconButton(onClick = {
                            val text = buildShareText(s)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(
                                Intent.createChooser(intent, "Share Results")
                            )
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                    }
                }
            )
        }
    ) { padding ->
        val s = session
        if (s == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { Text("Loading session…") }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Overall Score: ${s.overallScore}",
                        color = scoreColor(s.overallScore),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "Elbow @ release: ${s.elbowAngleAtRelease.toInt()}°  •  " +
                            "Knee bend: ${s.kneeBendDepth.toInt()}°",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Score breakdown bars
            item { ScoreBreakdown(session = s) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Show Ideal Form Reference",
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = compareIdeal,
                        onCheckedChange = { compareIdeal = it }
                    )
                }
            }
            if (compareIdeal) {
                item { IdealFormCard(session = s) }
            }
            items(s.phaseFeedback) { feedback ->
                PhaseCard(feedback = feedback)
            }

            // Drill recommendations
            item { DrillRecommendations(session = s) }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

// ---- Score breakdown bars ----

@Composable
private fun ScoreBreakdown(session: Session) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Score Breakdown",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            ScoreBar("Elbow Alignment", session.elbowFlareScore)
            ScoreBar("Elbow Extension", ((session.elbowAngleAtRelease / 180f) * 100f).toInt().coerceIn(0, 100))
            ScoreBar("Knee Bend", ((session.kneeBendDepth / 180f) * 100f).toInt().coerceIn(0, 100))
            ScoreBar("Balance", session.balanceScore)
            ScoreBar("Guide Hand", session.guideHandScore)
        }
    }
}

@Composable
private fun ScoreBar(label: String, score: Int) {
    val fraction by animateFloatAsState(
        targetValue = score / 100f,
        animationSpec = tween(durationMillis = 800),
        label = "bar-$label"
    )
    val color = colorForScore(score)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("$score", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

// ---- Ideal form card ----

@Composable
private fun IdealFormCard(session: Session) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Ideal Form Reference",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            ReferenceRow(
                metric = "Elbow extension at release",
                yours = "${session.elbowAngleAtRelease.toInt()}°",
                target = "150°–180°"
            )
            ReferenceRow(
                metric = "Knee bend (deepest)",
                yours = "${session.kneeBendDepth.toInt()}°",
                target = "100°–160°"
            )
            ReferenceRow(
                metric = "Elbow alignment under wrist",
                yours = "${session.elbowFlareScore}/100",
                target = "≥ 80"
            )
            ReferenceRow(
                metric = "Balance (hips over feet)",
                yours = "${session.balanceScore}/100",
                target = "≥ 80"
            )
            ReferenceRow(
                metric = "Guide hand passivity",
                yours = "${session.guideHandScore}/100",
                target = "≥ 80"
            )
        }
    }
}

@Composable
private fun ReferenceRow(metric: String, yours: String, target: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(metric, style = MaterialTheme.typography.bodyMedium)
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Yours: $yours",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                "Target: $target",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ---- Drill recommendations ----

private data class Drill(
    val name: String,
    val description: String,
    val reps: String
)

private fun getDrillsForSession(session: Session): List<Drill> {
    val drills = mutableListOf<Drill>()

    // Find the weakest areas and recommend drills
    if (session.elbowFlareScore < 80) {
        drills += Drill(
            name = "Wall Elbow Alignment",
            description = "Stand facing a wall with your shooting elbow touching. " +
                "Practice your shooting motion keeping your elbow in contact with the wall. " +
                "This trains your elbow to stay tucked under the ball.",
            reps = "3 sets of 15 reps"
        )
        drills += Drill(
            name = "One-Handed Form Shooting",
            description = "Shoot from 3–5 feet using only your shooting hand. " +
                "Focus on keeping your elbow pointed at the rim. " +
                "Remove the guide hand to isolate alignment.",
            reps = "50 makes"
        )
    }

    if (session.elbowAngleAtRelease < 150f) {
        drills += Drill(
            name = "Flick Drill",
            description = "Lie on your back and flick the ball straight up, " +
                "focusing on full arm extension and a clean wrist snap. " +
                "The ball should come straight back down to your hand.",
            reps = "3 sets of 20 flicks"
        )
        drills += Drill(
            name = "Follow-Through Freeze",
            description = "After every shot, hold your follow-through " +
                "with your arm fully extended until the ball hits the rim. " +
                "Your wrist should be relaxed in a gooseneck.",
            reps = "Every shot for 10 minutes"
        )
    }

    if (session.kneeBendDepth > 160f || session.kneeBendDepth < 100f) {
        drills += Drill(
            name = "Chair Squat Shots",
            description = "Place a chair behind you. Lower until you barely touch the seat, " +
                "then explode up into your shot. This grooves a consistent dip depth.",
            reps = "3 sets of 10"
        )
    }

    if (session.balanceScore < 80) {
        drills += Drill(
            name = "One-Foot Balance Holds",
            description = "Stand on your shooting-side foot for 30 seconds with your " +
                "eyes closed. This strengthens stabilizer muscles and body awareness. " +
                "Then practice shooting from a balanced, two-foot stance.",
            reps = "3 holds per foot, then 20 balanced shots"
        )
        drills += Drill(
            name = "Pause-and-Shoot",
            description = "Catch the ball, pause for a full second in your " +
                "shooting stance, check that your weight is centered, then shoot. " +
                "Slowing down builds the habit of starting balanced.",
            reps = "30 shots"
        )
    }

    if (session.guideHandScore < 80) {
        drills += Drill(
            name = "Guide Hand Off Early",
            description = "Practice shooting while deliberately removing your guide " +
                "hand from the ball just before the set point. " +
                "This trains your off-hand to be passive at release.",
            reps = "3 sets of 15 from close range"
        )
        drills += Drill(
            name = "Thumb Check",
            description = "After every shot, look at your guide hand thumb. " +
                "If it's pointing sideways or up, your guide hand stayed still. " +
                "If it's pointing at the rim, your guide hand pushed through.",
            reps = "Awareness drill — every shot for 10 min"
        )
    }

    // If everything is great
    if (drills.isEmpty()) {
        drills += Drill(
            name = "Volume Shooting",
            description = "Your form looks solid! Focus on high-rep game-speed " +
                "shooting from your favorite spots. Consistency comes from repetition.",
            reps = "200+ makes from 5 spots"
        )
        drills += Drill(
            name = "Pressure Free Throws",
            description = "Shoot free throws in sets of 10. Track your percentage. " +
                "Add stakes: don't leave until you hit 8/10.",
            reps = "5 sets of 10"
        )
    }

    return drills
}

@Composable
private fun DrillRecommendations(session: Session) {
    val drills = remember(session) { getDrillsForSession(session) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Recommended Drills",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            drills.forEach { drill ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        drill.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        drill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        drill.reps,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

// ---- Share helper ----

private fun buildShareText(session: Session): String = buildString {
    appendLine("FormCheck Results")
    appendLine("═══════════════════")
    appendLine("Overall Score: ${session.overallScore}/100")
    appendLine()
    appendLine("Elbow extension: ${session.elbowAngleAtRelease.toInt()}° (target 150–180°)")
    appendLine("Knee bend: ${session.kneeBendDepth.toInt()}° (target 100–160°)")
    appendLine("Elbow alignment: ${session.elbowFlareScore}/100")
    appendLine("Balance: ${session.balanceScore}/100")
    appendLine("Guide hand: ${session.guideHandScore}/100")
    appendLine()
    session.phaseFeedback.forEach { phase ->
        appendLine("${phase.phase.displayName()}: ${phase.score}/100")
        phase.notes.forEach { appendLine("  • $it") }
    }
    appendLine()
    appendLine("Analyzed with FormCheck")
}
