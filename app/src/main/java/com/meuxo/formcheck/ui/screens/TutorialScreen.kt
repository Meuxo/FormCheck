package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class TutorialStep(
    val title: String,
    val description: String,
    val tips: List<String>
)

private val tutorialSteps = listOf(
    TutorialStep(
        title = "Stance & Foot Placement",
        description = "Start with your feet shoulder-width apart. Your shooting-side foot should be slightly ahead of your non-shooting foot (staggered stance). Point your toes toward the basket. Bend your knees comfortably — this is where your power comes from.",
        tips = listOf(
            "Feet shoulder-width apart for a stable base",
            "Shooting foot slightly forward (1–2 inches)",
            "Knees bent at roughly 100°–160° — not too deep, not too stiff",
            "Weight balanced evenly on both feet"
        )
    ),
    TutorialStep(
        title = "Ball Placement (The \"Shot Pocket\")",
        description = "Hold the ball at your shot pocket — typically around your hip or waist on your shooting side. Your shooting hand should be under and behind the ball with fingers spread wide. The ball rests on your finger pads, NOT your palm.",
        tips = listOf(
            "Ball sits on finger pads, not the palm",
            "Spread fingers comfortably — thumb and pinky form a wide base",
            "Keep a small gap between the ball and your palm",
            "Shot pocket is consistent every time (muscle memory)"
        )
    ),
    TutorialStep(
        title = "Guide Hand Position",
        description = "Your non-shooting hand (guide hand) sits on the side of the ball to keep it steady. It does NOT push or flick the ball — it simply guides the ball up and releases cleanly. Think of it as a shelf that falls away.",
        tips = listOf(
            "Guide hand on the side of the ball, not behind it",
            "Light pressure — just enough to stabilize",
            "Guide hand stays still at release (does NOT push forward)",
            "After release, guide hand should point sideways, not at the rim"
        )
    ),
    TutorialStep(
        title = "The Lift (Loading Phase)",
        description = "As you begin your shot, dip the ball slightly and bend your knees simultaneously. Then drive upward — your legs and arms work together in one smooth motion. The ball travels from your shot pocket up through your set point in a straight line toward the basket.",
        tips = listOf(
            "Legs and arms move together — it's one fluid motion",
            "Slight dip before the lift adds rhythm and power",
            "Ball travels in a straight line, not a loop or arc to the side",
            "Your power comes from your legs, not your arms"
        )
    ),
    TutorialStep(
        title = "Set Point & Elbow Alignment",
        description = "Bring the ball up to your set point — at or just above your forehead. Your shooting elbow should be directly under the ball, forming an \"L\" shape (roughly 90°). The elbow should point toward the basket, not flare out to the side.",
        tips = listOf(
            "Set point at or above forehead level for a quick, high release",
            "Elbow forms a 90° angle (the \"L\")",
            "Elbow tucked in — aligned under the wrist and ball",
            "Keep the ball in front of you, not behind your head"
        )
    ),
    TutorialStep(
        title = "The Release & Follow-Through",
        description = "Extend your arm fully toward the basket. Snap your wrist forward, releasing the ball off your index and middle fingers. Your elbow should finish fully extended (150°–180°). Hold your follow-through — your wrist should be relaxed and your fingers pointing down toward the rim (\"gooseneck\").",
        tips = listOf(
            "Full arm extension — elbow finishes at 150°–180°",
            "Ball rolls off index and middle fingers last",
            "Snap the wrist — fingers point down at the rim",
            "Hold the follow-through until the ball hits the rim",
            "Guide hand stays passive and separates cleanly"
        )
    ),
    TutorialStep(
        title = "Arc & Aim",
        description = "A good shot has a high arc — roughly 45°–52° launch angle. Aim for the back of the rim or just over the front. Higher arc gives the ball a larger \"window\" to go through the hoop. Flat shots have a much smaller margin for error.",
        tips = listOf(
            "Aim for a 45°–52° arc",
            "Higher arc = bigger target window",
            "Pick a consistent aim point (back of rim, center of hoop)",
            "Flat shots are harder to make — get it up!"
        )
    )
)

private data class FilmingTip(val text: String)

private val filmingTips = listOf(
    FilmingTip("Film from a 45° angle on your shooting-hand side for best results"),
    FilmingTip("Get your full body in frame — head to feet"),
    FilmingTip("Use good lighting and a plain background"),
    FilmingTip("Set the camera at waist height, about 8–10 feet away"),
    FilmingTip("Use a tripod or prop your phone up — steady footage matters"),
    FilmingTip("Record one shot at a time for the most accurate analysis")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("How to Shoot") },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.size(4.dp)) }

            // Intro card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.SportsBasketball,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = "Master the fundamentals of a great basketball shot. " +
                                "Follow these 7 steps, then record yourself to see how you measure up!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Step-by-step tutorial cards
            itemsIndexed(tutorialSteps) { index, step ->
                StepCard(stepNumber = index + 1, step = step)
            }

            // Filming tips section
            item {
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Tips for Filming Your Shot",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Best camera angle card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Recommended Camera Angle",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            "For the most accurate analysis, film from a 45-degree angle " +
                                "on your shooting-hand side. This gives the app a clear view " +
                                "of both your elbow extension and elbow alignment at the same time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(Modifier.size(2.dp))
                        Text(
                            "Why not a side view?",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            "A pure side view is great for elbow extension, knee bend, " +
                                "and set point height — but it hides elbow flare (whether " +
                                "your elbow drifts outward), which is one of the most common " +
                                "shooting mistakes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(Modifier.size(2.dp))
                        Text(
                            "Why not a front view?",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            "A front view catches elbow flare well, but it compresses " +
                                "the depth of your arm, making elbow extension and knee bend " +
                                "angles look flatter than they really are.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(Modifier.size(2.dp))
                        Text(
                            "The 45-degree angle is the sweet spot — it captures all " +
                                "six metrics the app measures with the best accuracy. " +
                                "Stand about 8–10 feet away with the camera at waist height.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        filmingTips.forEach { tip ->
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    tip.text,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            // What the app measures
            item {
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "What FormCheck Measures",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            item {
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
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricExplainer(
                            name = "Elbow Extension",
                            target = "150°–180°",
                            explanation = "How fully you extend your arm at release"
                        )
                        MetricExplainer(
                            name = "Elbow Alignment",
                            target = "Score ≥ 80",
                            explanation = "Whether your elbow stays under the ball (not flaring out)"
                        )
                        MetricExplainer(
                            name = "Knee Bend",
                            target = "100°–160°",
                            explanation = "How much you load your legs for power"
                        )
                        MetricExplainer(
                            name = "Set Point Height",
                            target = "At or above forehead",
                            explanation = "Where you hold the ball before releasing"
                        )
                        MetricExplainer(
                            name = "Balance",
                            target = "Score ≥ 80",
                            explanation = "Hips centered over feet during the shot"
                        )
                        MetricExplainer(
                            name = "Guide Hand",
                            target = "Score ≥ 80",
                            explanation = "How passive your off-hand stays at release"
                        )
                    }
                }
            }

            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun StepCard(stepNumber: Int, step: TutorialStep) {
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
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$stepNumber",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = step.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(10.dp))
            step.tips.forEach { tip ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        tip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricExplainer(name: String, target: String, explanation: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                target,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
