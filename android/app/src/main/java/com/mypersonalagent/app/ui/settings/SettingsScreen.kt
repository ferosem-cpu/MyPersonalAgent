package com.mypersonalagent.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val testResult by viewModel.testResult.collectAsState()

    var serverUrl by remember { mutableStateOf("") }
    var apiToken by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        serverUrl = state.serverUrl
        apiToken = state.apiToken
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL (e.g. http://100.x.x.x:8500)") },
        )
        OutlinedTextField(
            value = apiToken,
            onValueChange = { apiToken = it },
            label = { Text("API token") },
        )
        Button(onClick = { viewModel.save(serverUrl, apiToken) }) {
            Text("Save")
        }
        Button(onClick = { viewModel.save(serverUrl, apiToken); viewModel.testConnection() }) {
            Text("Test connection")
        }
        when (val result = testResult) {
            is ConnectionTestResult.Idle -> {}
            is ConnectionTestResult.Testing -> Text("Testing...")
            is ConnectionTestResult.Success -> Text("Connected ✓")
            is ConnectionTestResult.Failure -> Text("Failed: ${result.message}")
        }
    }
}
