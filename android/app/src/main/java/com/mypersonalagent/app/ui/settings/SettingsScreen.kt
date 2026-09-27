package com.mypersonalagent.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val saveMessage by viewModel.saveMessage.collectAsState()
    val appAliases by viewModel.appAliases.collectAsState()
    val launchResult by viewModel.launchResult.collectAsState()
    val driveAccountEmail by viewModel.driveAccountEmail.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()
    val driveFolderUri by viewModel.driveFolderUri.collectAsState()
    val context = LocalContext.current

    val driveSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.handleDriveSignInResult(result.data) }

    val driveFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.setDriveFolderUri(uri.toString())
        }
    }

    var hydrated by remember { mutableStateOf(false) }
    var telegramBotToken by remember { mutableStateOf("") }
    var telegramChatId by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(DEFAULT_LLM_BASE_URL) }
    var model by remember { mutableStateOf(DEFAULT_LLM_MODEL) }
    var openAppQuery by remember { mutableStateOf("") }
    var newAliasName by remember { mutableStateOf("") }
    var newAliasPackage by remember { mutableStateOf("") }

    LaunchedEffect(state.ready) {
        if (state.ready && !hydrated) {
            telegramBotToken = state.telegramBotToken
            telegramChatId = state.telegramChatId
            apiKey = ""
            baseUrl = state.customBaseUrl.ifBlank { DEFAULT_LLM_BASE_URL }
            model = state.customModel.ifBlank { DEFAULT_LLM_MODEL }
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
            "One gateway. Paste the endpoint and key once. Change Grok / Claude / NVIDIA on your FreeLLM router — the APK never needs a rebuild.",
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

        SettingsCard(
            title = "LLM gateway",
            subtitle = "OpenAI-compatible /v1 endpoint. Route every backend through FreeLLM and only change this URL or key here.",
        ) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Endpoint") },
                placeholder = { Text(DEFAULT_LLM_BASE_URL) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            KeyField(
                label = "API key",
                value = apiKey,
                onValueChange = { apiKey = it },
                hint = "Reeller / FreeLLM key",
                saved = state.customApiKey.isNotBlank(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model id") },
                placeholder = { Text(DEFAULT_LLM_MODEL) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(
                "Use a model id your gateway already exposes. Add Grok, Claude, NVIDIA, etc. inside FreeLLM — not in this app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.customApiKey.isNotBlank()) {
                TextButton(onClick = { viewModel.clearGatewayKey(); apiKey = "" }) {
                    Text("Remove saved key")
                }
            }
            Button(
                onClick = { viewModel.saveGateway(apiKey = apiKey, baseUrl = baseUrl, model = model) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Save gateway") }
        }

        SettingsCard(title = "Telegram reminders", subtitle = "Optional. Create a bot with @BotFather, message it, then paste the token and chat id.") {
            KeyField("Bot token", telegramBotToken, { telegramBotToken = it }, "123456:ABC…", saved = state.telegramBotToken.isNotBlank())
            OutlinedTextField(value = telegramChatId, onValueChange = { telegramChatId = it }, label = { Text("Chat ID") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { viewModel.saveTelegram(telegramBotToken, telegramChatId) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save Telegram")
            }
        }

        SettingsCard(
            title = "Google Drive",
            subtitle = "Drop/share files into the app to copy them into Pictures, Documents, Code, or Others. First choose the Drive folder. Optional Google sign-in also backs up todos/log/notes/contacts into a visible MyPersonalAgent folder.",
        ) {
            Text(
                if (driveFolderUri.isNullOrBlank()) "File drop folder: not set"
                else "File drop folder: connected",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { driveFolderLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (driveFolderUri.isNullOrBlank()) "Choose Drive folder" else "Change Drive folder")
            }
            Text(
                "In the system picker: menu → Drive → MyPersonalAgent (create it if needed). This is what makes file drop work, even if Google sign-in fails.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (driveAccountEmail != null) {
                Text("Signed in as $driveAccountEmail", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.backupNow() }) { Text("Back up now") }
                    OutlinedButton(onClick = { viewModel.restoreLatestBackup() }) { Text("Restore") }
                }
                TextButton(onClick = { viewModel.driveSignOut() }) { Text("Sign out") }
            } else {
                OutlinedButton(onClick = { driveSignInLauncher.launch(viewModel.driveSignInIntent()) }) {
                    Text("Optional: Google sign-in for JSON backup")
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
