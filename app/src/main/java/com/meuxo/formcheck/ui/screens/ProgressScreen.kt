package com.meuxo.formcheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meuxo.formcheck.domain.model.MetricSnapshot
import com.meuxo.formcheck.domain.usecase.GetProgressUseCase
import com.meuxo.formcheck.ui.components.MetricChart
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ProgressViewModel @Inject constructor(
    getProgress: GetProgressUseCase
) : ViewModel() {
    val snapshots: StateFlow<List<MetricSnapshot>> = getProgress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    onBack: () -> Unit,
    viewModel: ProgressViewModel = hiltViewModel()
) {
    val data by viewModel.snapshots.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Progress") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (data.isEmpty()) {
            Text(
                text = "No sessions yet — record or upload a shot to start tracking.",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                MetricChart("Overall Score", data.map { it.overallScore.toFloat() })
            }
            item {
                MetricChart("Elbow Angle at Release",
                    data.map { it.elbowAngleAtRelease })
            }
            item {
                MetricChart("Knee Bend Depth", data.map { it.kneeBendDepth })
            }
            item {
                MetricChart("Balance Score", data.map { it.balanceScore.toFloat() })
            }
        }
    }
}
