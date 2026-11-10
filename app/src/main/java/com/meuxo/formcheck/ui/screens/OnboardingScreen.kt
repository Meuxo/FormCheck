package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.meuxo.formcheck.data.settings.SettingsRepository
import com.meuxo.formcheck.domain.model.Units
import com.meuxo.formcheck.domain.model.UserSettings
import com.meuxo.formcheck.tracking.Side
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(val repo: SettingsRepository) : ViewModel() {
    val settings = repo.settings
    suspend fun update(t: (UserSettings) -> UserSettings) = repo.update(t)
}

/** Parses a height typed as cm, or as feet + inches. Returns cm or null. */
fun parseHeightCm(units: Units, cm: String, ft: String, inch: String): Double? = when (units) {
    Units.METRIC -> cm.toDoubleOrNull()?.takeIf { it in 100.0..250.0 }
    Units.IMPERIAL -> {
        val f = ft.toIntOrNull(); val i = inch.toDoubleOrNull() ?: 0.0
        if (f == null) null else ((f * 12 + i) * 2.54).takeIf { it in 100.0..250.0 }
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    var hand by remember { mutableStateOf<Side?>(null) }
    var units by remember { mutableStateOf(Units.IMPERIAL) }
    var cm by remember { mutableStateOf("") }
    var ft by remember { mutableStateOf("") }
    var inch by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val heightCm = parseHeightCm(units, cm, ft, inch)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Welcome to FormCheck", style = MaterialTheme.typography.headlineMedium)
        Text("Two quick questions so the measurements fit you. Everything runs on this phone; no video is uploaded.")

        Text("Which hand do you shoot with?", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Side.entries.forEach { s ->
                FilterChip(selected = hand == s, onClick = { hand = s }, label = { Text(s.label()) },
                    modifier = Modifier.semantics { contentDescription = "${s.label()} hand" })
            }
        }
        Text("The app also checks which hand is under the ball and warns you if it does not match.",
            style = MaterialTheme.typography.bodySmall)

        Text("How tall are you?", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Units.entries.forEach { u ->
                FilterChip(selected = units == u, onClick = { units = u }, label = { Text(if (u == Units.METRIC) "cm" else "ft / in") })
            }
        }
        if (units == Units.METRIC) {
            OutlinedTextField(cm, { cm = it }, label = { Text("Height (cm)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(ft, { ft = it }, label = { Text("Feet") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(inch, { inch = it }, label = { Text("Inches") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
        }
        Text("Height is used to convert pixels into real distances when the ball size cannot be used. " +
            "Without it, distances such as release height show as Not measured.", style = MaterialTheme.typography.bodySmall)

        Button(
            onClick = {
                scope.launch {
                    vm.update { it.copy(onboarded = true, shootingHand = hand, heightCm = heightCm, units = units) }
                    onDone()
                }
            },
            enabled = hand != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (heightCm == null) "Continue without height" else "Continue") }
    }
}
