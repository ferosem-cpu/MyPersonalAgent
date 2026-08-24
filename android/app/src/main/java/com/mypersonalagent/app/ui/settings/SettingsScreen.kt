package com.mypersonalagent.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private data class ProviderSpec(
    val id: String,
    val label: String,
    val keyHint: String,
    val modelHint: String,
)

private val LLM_PROVIDERS = listOf(
    ProviderSpec("auto", "Auto (fallback)", "", ""),
    ProviderSpec("nvidia", "NVIDIA", "nvapi-…", "nvidia/llama-3.3-nemotron-super-49b-v1.5"),
    ProviderSpec("anthropic", "Anthropic", "sk-ant-…", "claude-sonnet-5"),
    ProviderSpec("openai", "OpenAI", "sk-…", "gpt-4o"),
    ProviderSpec("google", "Google", "AIza…", "gemini-2.0-flash"),
    ProviderSpec("openrouter", "OpenRouter", "sk-or-…", "anthropic/claude-sonnet-5"),
    ProviderSpec("grok", "Grok", "xai-…", "grok-3"),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val saveMessage by viewModel.saveMessage.collectAsState()
    val appAliases by viewModel.appAliases.collectAsState()
    val launchResult by viewModel.launchResult.collectAsState()
    val driveAccountEmail by viewModel.driveAccountEmail.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()

    val driveSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.handleDriveSignInResult(result.data) }

    var hydrated by remember { mutableStateOf(false) }
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

    LaunchedEffect(state.ready) {
        if (state.ready && !hydrated) {
            telegramBotToken = state.telegramBotToken
            telegramChatId = state.telegramChatId
            llmProvider = state.llmProvider.ifBlank { "auto" }
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
            hydrated = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This app runs entirely on your phone. Paste API keys below and tap Save all keys once.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (saveMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(saveMessage ?: "", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    TextButton(onClick = { viewModel.clearSaveMessage() }) { Text("OK") }
                }
            }
        }

        SettingsCard(title = "AI chat keys", subtitle = "One save writes every key you filled in. Leaving a field blank keeps the key already stored.") {
            Text("Preferred provider", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LLM_PROVIDERS.forEach { spec ->
                    FilterChip(
                        selected = llmProvider == spec.id,
                        onClick = { llmProvider = spec.id },
                        label = { Text(spec.label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            KeyField("NVIDIA API key", nvidiaApiKey, { nvidiaApiKey = it }, LLM_PROVIDERS[1].keyHint, saved = state.nvidiaApiKey.isNotBlank())
            OutlinedTextField(value = nvidiaModel, onValueChange = { nvidiaModel = it }, label = { Text("NVIDIA model") }, placeholder = { Text(LLM_PROVIDERS[1].modelHint) }, modifier = Modifier.fillMaxWidth())
            if (state.nvidiaApiKey.isNotBlank()) TextButton(onClick = { viewModel.clearProviderKey("nvidia"); nvidiaApiKey = "" }) { Text("Remove NVIDIA key") }

            KeyField("Anthropic API key", anthropicApiKey, { anthropicApiKey = it }, LLM_PROVIDERS[2].keyHint, saved = state.anthropicApiKey.isNotBlank())
            OutlinedTextField(value = anthropicModel, onValueChange = { anthropicModel = it }, label = { Text("Anthropic model") }, placeholder = { Text(LLM_PROVIDERS[2].modelHint) }, modifier = Modifier.fillMaxWidth())
            if (state.anthropicApiKey.isNotBlank()) TextButton(onClick = { viewModel.clearProviderKey("anthropic"); anthropicApiKey = "" }) { Text("Remove Anthropic key") }

            KeyField("OpenAI API key", openaiApiKey, { openaiApiKey = it }, LLM_PROVIDERS[3].keyHint, saved = state.openaiApiKey.isNotBlank())
            OutlinedTextField(value = openaiModel, onValueChange = { openaiModel = it }, label = { Text("OpenAI model") }, placeholder = { Text(LLM_PROVIDERS[3].modelHint) }, modifier = Modifier.fillMaxWidth())
            if (state.openaiApiKey.isNotBlank()) TextButton(onClick = { viewModel.clearProviderKey("openai"); openaiApiKey = "" }) { Text("Remove OpenAI key") }

            KeyField("Google API key", googleApiKey, { googleApiKey = it }, LLM_PROVIDERS[4].keyHint, saved = state.googleApiKey.isNotBlank())
            OutlinedTextField(value = googleModel, onValueChange = { googleModel = it }, label = { Text("Google model") }, placeholder = { Text(LLM_PROVIDERS[4].modelHint) }, modifier = Modifier.fillMaxWidth())
            if (state.googleApiKey.isNotBlank()) TextButton(onClick = { viewModel.clearProviderKey("google"); googleApiKey = "" }) { Text("Remove Google key") }

            KeyField("OpenRouter API key", openrouterApiKey, { openrouterApiKey = it }, LLM_PROVIDERS[5].keyHint, saved = state.openrouterApiKey.isNotBlank())
            OutlinedTextField(value = openrouterModel, onValueChange = { openrouterModel = it }, label = { Text("OpenRouter model") }, placeholder = { Text(LLM_PROVIDERS[5].modelHint) }, modifier = Modifier.fillMaxWidth())
            if (state.openrouterApiKey.isNotBlank()) TextButton(onClick = { viewModel.clearProviderKey("openrouter"); openrouterApiKey = "" }) { Text("Remove OpenRouter key") }

            KeyField("Grok API key", grokApiKey, { grokApiKey = it }, LLM_PROVIDERS[6].keyHint, saved = state.grokApiKey.isNotBlank())
            OutlinedTextField(value = grokModel, onValueChange = { grokModel = it }, label = { Text("Grok model") }, placeholder = { Text(LLM_PROVIDERS[6].modelHint) }, modifier = Modifier.fillMaxWidth())
            if (state.grokApiKey.isNotBlank()) TextButton(onClick = { viewModel.clearProviderKey("grok"); grokApiKey = "" }) { Text("Remove Grok key") }

            Button(
                onClick = {
                    viewModel.saveAllKeys(
                        provider = llmProvider,
                        nvidiaApiKey = nvidiaApiKey,
                        nvidiaModel = nvidiaModel,
                        anthropicApiKey = anthropicApiKey,
                        anthropicModel = anthropicModel,
                        openaiApiKey = openaiApiKey,
                        openaiModel = openaiModel,
                        googleApiKey = googleApiKey,
                        googleModel = googleModel,
                        openrouterApiKey = openrouterApiKey,
                        openrouterModel = openrouterModel,
                        grokApiKey = grokApiKey,
                        grokModel = grokModel,
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Save all keys") }
        }

        SettingsCard(title = "Telegram reminders", subtitle = "Optional. Create a bot with @BotFather, message it, then paste the token and chat id.") {
            KeyField("Bot token", telegramBotToken, { telegramBotToken = it }, "123456:ABC…", saved = state.telegramBotToken.isNotBlank())
            OutlinedTextField(value = telegramChatId, onValueChange = { telegramChatId = it }, label = { Text("Chat ID") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { viewModel.saveTelegram(telegramBotToken, telegramChatId) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save Telegram")
            }
        }

        SettingsCard(title = "Google Drive backup", subtitle = "Optional private backup of todos, log, notes, and contacts.") {
            if (driveAccountEmail != null) {
                Text("Signed in as $driveAccountEmail", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.backupNow() }) { Text("Back up now") }
                    OutlinedButton(onClick = { viewModel.restoreLatestBackup() }) { Text("Restore") }
                }
                TextButton(onClick = { viewModel.driveSignOut() }) { Text("Sign out") }
            } else {
                Button(onClick = { driveSignInLauncher.launch(viewModel.driveSignInIntent()) }) {
                    Text("Sign in to Google Drive")
                }
            }
            when (val status = backupStatus) {
                is DriveBackupStatus.Idle -> {}
                is DriveBackupStatus.Working -> Text("Working…")
                is DriveBackupStatus.Success -> Text(status.message)
                is DriveBackupStatus.Failure -> Text("Failed: ${status.message}", color = MaterialTheme.colorScheme.error)
            }
        }

        SettingsCard(title = "Open app", subtitle = "Local only — type an alias or package name.") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = openAppQuery,
                    onValueChange = { openAppQuery = it },
                    label = { Text("Alias or package") },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { viewModel.openApp(openAppQuery) }) { Text("Open") }
            }
            launchResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Text("Aliases", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            appAliases.forEach { (alias, packageName) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(alias, style = MaterialTheme.typography.bodyMedium)
                        Text(packageName, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { viewModel.removeAppAlias(alias) }) { Text("Remove") }
                }
            }
            OutlinedTextField(value = newAliasName, onValueChange = { newAliasName = it }, label = { Text("Alias") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = newAliasPackage, onValueChange = { newAliasPackage = it }, label = { Text("Package name") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                viewModel.addAppAlias(newAliasName, newAliasPackage)
                newAliasName = ""
                newAliasPackage = ""
            }) { Text("Add alias") }
        }
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun KeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    saved: Boolean,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(if (saved) "$label · saved" else label) },
        placeholder = { Text(hint) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide key" else "Show key",
                )
            }
        },
    )
}
