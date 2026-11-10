package com.meuxo.formcheck.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meuxo.formcheck.domain.model.SessionParams
import com.meuxo.formcheck.domain.model.SessionType

/** How to film each mode. Shown before recording or uploading. */
fun SessionType.filmingGuide(): List<String> = when (this) {
    SessionType.FORM -> listOf(
        "Side view (camera at 90° to the shot) measures knees, elbow, release angle and arc.",
        "Front view (camera facing you) measures elbow alignment and guide hand.",
        "An angled view gives the fewest reliable metrics.",
        "Whole body and the ball in frame, phone still, 60 fps if your phone offers it."
    )
    SessionType.SHOOTAROUND -> listOf(
        "Phone on a tripod or propped up, not moving.",
        "Rim and shooter both in frame; part of the court lines visible for the shot chart."
    )
    SessionType.DRIBBLE -> listOf(
        "Front view, whole body and the floor in frame, phone still.",
        "Stand still for the first 3 seconds so your standing hip height can be measured."
    )
    SessionType.JUMP -> listOf(
        "Side or front view, whole body including feet and floor in frame, phone still.",
        "Use the highest frame rate available. At 30 fps one frame of error is several centimetres."
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeScreen(
    type: SessionType,
    onBack: () -> Unit,
    onRecord: () -> Unit,
    onEvent: (NewSessionEvent) -> Unit,
    extraParams: (SessionParams) -> SessionParams = { it },
    header: @Composable () -> Unit = {},
    vm: NewSessionViewModel = hiltViewModel()
) {
    val busy by vm.busy.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm) {
        vm.events.collect { e -> if (e is NewSessionEvent.Failed) error = e.message else onEvent(e) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.importUri(type, it, extraParams) }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(type.label) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(type.blurb(), style = MaterialTheme.typography.bodyLarge)
            header()
            Text("Filming", style = MaterialTheme.typography.titleMedium)
            type.filmingGuide().forEach { Text("• $it") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRecord, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Default.Videocam, null); Spacer(Modifier.size(8.dp)); Text("Record")
            }
            OutlinedButton(
                onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Icon(Icons.Default.Upload, null); Spacer(Modifier.size(8.dp)); Text("Upload a video") }
            if (busy) Column { CircularProgressIndicator(); Text("Copying video into the app…") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Videos are copied into the app's private storage so they stay available after a restart. " +
                "Photos are not supported: a still frame cannot show the dip, release or follow-through.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
