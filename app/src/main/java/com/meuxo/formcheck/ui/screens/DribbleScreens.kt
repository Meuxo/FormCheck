package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meuxo.formcheck.domain.model.SessionResult
import com.meuxo.formcheck.dribble.DrillLibrary
import com.meuxo.formcheck.scoring.ScoringConfig

/** Drill library list for the dribbling mode screen (spec 7 step 1–2). */
@Composable
fun DrillPicker(selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Choose a drill", style = MaterialTheme.typography.titleMedium)
        DrillLibrary.drills.forEach { d ->
            Card(Modifier.fillMaxWidth().selectable(selected == d.id, enabled = d.supported, role = Role.RadioButton) { onSelect(d.id) },
                colors = CardDefaults.cardColors(containerColor = if (selected == d.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == d.id, onClick = null, enabled = d.supported)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(d.name, fontWeight = FontWeight.SemiBold)
                        Text(d.description, style = MaterialTheme.typography.bodySmall)
                        if (d.supported) Text("${d.targetSeconds} s · ${d.pattern.description}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text("Start by standing still for 3 seconds (standing hip height calibration), then begin the drill.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun DribbleSections(r: SessionResult) {
    val d = DrillLibrary.byId(r.drillId) ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Expected pattern: ${d.pattern.description}.")
            d.heightTarget?.let { k -> ScoringConfig.dribbleHeightTargets[k]?.let { Text("Height target: ${it.source}", style = MaterialTheme.typography.bodySmall) } }
            Text("Ball protection and eyes up are proxies: there is no defender, and eye direction is estimated from head angle.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
