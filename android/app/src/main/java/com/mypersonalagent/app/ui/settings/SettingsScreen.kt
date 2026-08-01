package com.mypersonalagent.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val appAliases by viewModel.appAliases.collectAsState()
    val launchResult by viewModel.launchResult.collectAsState()

    var serverUrl by remember { mutableStateOf("") }
    var apiToken by remember { mutableStateOf("") }
    var openAppQuery by remember { mutableStateOf("") }
    var newAliasName by remember { mutableStateOf("") }
    var newAliasPackage by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        serverUrl = state.serverUrl
        apiToken = state.apiToken
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
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

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Open app", style = MaterialTheme.typography.titleMedium)
        Text(
            "Purely local - no server needed. Type an alias below, or an app's package name.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = openAppQuery,
                onValueChange = { openAppQuery = it },
                label = { Text("Alias or package name") },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { viewModel.openApp(openAppQuery) },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Open") }
        }
        launchResult?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("App aliases", style = MaterialTheme.typography.titleMedium)
        Text(
            "Map a short name (e.g. \"swiggy\") to an app's package name so \"Open app\" and voice commands can find it.",
            style = MaterialTheme.typography.bodySmall,
        )
        appAliases.forEach { (alias, packageName) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(alias, style = MaterialTheme.typography.bodyMedium)
                    Text(packageName, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { viewModel.removeAppAlias(alias) }) { Text("Remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newAliasName,
                onValueChange = { newAliasName = it },
                label = { Text("Alias") },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = newAliasPackage,
                onValueChange = { newAliasPackage = it },
                label = { Text("Package name") },
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }
        Button(onClick = {
            viewModel.addAppAlias(newAliasName, newAliasPackage)
            newAliasName = ""
            newAliasPackage = ""
        }) { Text("Add alias") }
    }
}
