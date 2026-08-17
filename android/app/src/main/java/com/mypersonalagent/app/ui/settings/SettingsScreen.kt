package com.mypersonalagent.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    val driveAccountEmail by viewModel.driveAccountEmail.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()

    val driveSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.handleDriveSignInResult(result.data) }

    var serverUrl by remember { mutableStateOf("") }
    var apiToken by remember { mutableStateOf("") }
    var telegramBotToken by remember { mutableStateOf("") }
    var telegramChatId by remember { mutableStateOf("") }
    var llmProvider by remember { mutableStateOf("auto") }
    var nvidiaApiKey by remember { mutableStateOf("") }
    var nvidiaModel by remember { mutableStateOf("") }
    var anthropicApiKey by remember { mutableStateOf("") }
    var anthropicModel by remember { mutableStateOf("") }
    var openaiApiKey by remember { mutableStateOf("") }
    var openaiModel by remember { mutableStateOf("") }
    var googleApiKey by remember { mutableStateOf("") }
    var googleModel by remember { mutableStateOf("") }
    var openrouterApiKey by remember { mutableStateOf("") }
    var openrouterModel by remember { mutableStateOf("") }
    var grokApiKey by remember { mutableStateOf("") }
    var grokModel by remember { mutableStateOf("") }
    var openAppQuery by remember { mutableStateOf("") }
    var newAliasName by remember { mutableStateOf("") }
    var newAliasPackage by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        serverUrl = state.serverUrl
        apiToken = state.apiToken
        telegramBotToken = state.telegramBotToken
        telegramChatId = state.telegramChatId
        llmProvider = state.llmProvider
        nvidiaApiKey = state.nvidiaApiKey
        nvidiaModel = state.nvidiaModel
        anthropicApiKey = state.anthropicApiKey
        anthropicModel = state.anthropicModel
        openaiApiKey = state.openaiApiKey
        openaiModel = state.openaiModel
        googleApiKey = state.googleApiKey
        googleModel = state.googleModel
        openrouterApiKey = state.openrouterApiKey
        openrouterModel = state.openrouterModel
        grokApiKey = state.grokApiKey
        grokModel = state.grokModel
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

        Text("Telegram reminders", style = MaterialTheme.typography.titleMedium)
        Text(
            "Optional - sent directly from the phone, no laptop or server needed. " +
                "Create a bot via @BotFather to get a token, then message the bot once and use " +
                "https://api.telegram.org/bot<token>/getUpdates to find your chat id.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = telegramBotToken,
            onValueChange = { telegramBotToken = it },
            label = { Text("Bot token") },
        )
        OutlinedTextField(
            value = telegramChatId,
            onValueChange = { telegramChatId = it },
            label = { Text("Chat ID") },
        )
        Button(onClick = { viewModel.saveTelegram(telegramBotToken, telegramChatId) }) {
            Text("Save Telegram settings")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Multi-Provider Agent Chat", style = MaterialTheme.typography.titleMedium)
        Text(
            "Configure your LLM API keys for on-device AI chat with tools. " +
                "In Auto mode, the app will automatically fall back to the next available key if one fails.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = llmProvider,
            onValueChange = { llmProvider = it },
            label = { Text("Active Provider (auto, nvidia, anthropic, openai, google, openrouter, grok)") },
        )
        Button(onClick = { viewModel.saveLlmProvider(llmProvider) }) {
            Text("Save Active Provider Choice")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text("1. NVIDIA NIM", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = nvidiaApiKey,
            onValueChange = { nvidiaApiKey = it },
            label = { Text("NVIDIA API key (nvapi-...)") },
        )
        OutlinedTextField(
            value = nvidiaModel,
            onValueChange = { nvidiaModel = it },
            label = { Text("Model (default: nvidia/llama-3.3-nemotron-super-49b-v1.5)") },
        )
        Button(onClick = { viewModel.saveNvidia(nvidiaApiKey, nvidiaModel) }) {
            Text("Save NVIDIA settings")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text("2. Anthropic", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = anthropicApiKey,
            onValueChange = { anthropicApiKey = it },
            label = { Text("Anthropic API key (sk-ant-...)") },
        )
        OutlinedTextField(
            value = anthropicModel,
            onValueChange = { anthropicModel = it },
            label = { Text("Model (default: claude-sonnet-5)") },
        )
        Button(onClick = { viewModel.saveAnthropic(anthropicApiKey, anthropicModel) }) {
            Text("Save Anthropic settings")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text("3. OpenAI", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = openaiApiKey,
            onValueChange = { openaiApiKey = it },
            label = { Text("OpenAI API key (sk-...)") },
        )
        OutlinedTextField(
            value = openaiModel,
            onValueChange = { openaiModel = it },
            label = { Text("Model (default: gpt-4o)") },
        )
        Button(onClick = { viewModel.saveOpenai(openaiApiKey, openaiModel) }) {
            Text("Save OpenAI settings")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text("4. Google Gemini", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = googleApiKey,
            onValueChange = { googleApiKey = it },
            label = { Text("Google API key") },
        )
        OutlinedTextField(
            value = googleModel,
            onValueChange = { googleModel = it },
            label = { Text("Model (default: gemini-2.0-flash)") },
        )
        Button(onClick = { viewModel.saveGoogle(googleApiKey, googleModel) }) {
            Text("Save Google settings")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text("5. OpenRouter", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = openrouterApiKey,
            onValueChange = { openrouterApiKey = it },
            label = { Text("OpenRouter API key (sk-or-...)") },
        )
        OutlinedTextField(
            value = openrouterModel,
            onValueChange = { openrouterModel = it },
            label = { Text("Model (default: anthropic/claude-sonnet-5)") },
        )
        Button(onClick = { viewModel.saveOpenrouter(openrouterApiKey, openrouterModel) }) {
            Text("Save OpenRouter settings")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Text("6. xAI Grok", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = grokApiKey,
            onValueChange = { grokApiKey = it },
            label = { Text("Grok API key") },
        )
        OutlinedTextField(
            value = grokModel,
            onValueChange = { grokModel = it },
            label = { Text("Model (default: grok-beta)") },
        )
        Button(onClick = { viewModel.saveGrok(grokApiKey, grokModel) }) {
            Text("Save Grok settings")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Google Drive backup", style = MaterialTheme.typography.titleMedium)
        Text(
            "Optional - backs up your todos, log, notes, and contacts straight from the " +
                "phone to your own Google Drive (a private app-only file, not visible in " +
                "your normal Drive). Runs automatically once a day when signed in, or trigger " +
                "it manually below.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (driveAccountEmail != null) {
            Text("Signed in as $driveAccountEmail", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.backupNow() }) { Text("Back up now") }
                Button(onClick = { viewModel.restoreLatestBackup() }) { Text("Restore latest backup") }
            }
            Button(onClick = { viewModel.driveSignOut() }) { Text("Sign out") }
        } else {
            Button(onClick = { driveSignInLauncher.launch(viewModel.driveSignInIntent()) }) {
                Text("Sign in to Google Drive")
            }
        }
        when (val status = backupStatus) {
            is DriveBackupStatus.Idle -> {}
            is DriveBackupStatus.Working -> Text("Working...")
            is DriveBackupStatus.Success -> Text(status.message)
            is DriveBackupStatus.Failure -> Text("Failed: ${status.message}")
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
