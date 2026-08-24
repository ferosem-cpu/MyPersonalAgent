package com.mypersonalagent.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.common.api.ApiException
import com.mypersonalagent.app.data.repo.DriveBackupRepository
import com.mypersonalagent.app.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DriveBackupStatus {
    object Idle : DriveBackupStatus
    object Working : DriveBackupStatus
    data class Success(val message: String) : DriveBackupStatus
    data class Failure(val message: String) : DriveBackupStatus
}

data class SettingsUiState(
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val llmProvider: String = "auto",
    val anthropicApiKey: String = "",
    val anthropicModel: String = "",
    val nvidiaApiKey: String = "",
    val nvidiaModel: String = "",
    val openaiApiKey: String = "",
    val openaiModel: String = "",
    val googleApiKey: String = "",
    val googleModel: String = "",
    val openrouterApiKey: String = "",
    val openrouterModel: String = "",
    val grokApiKey: String = "",
    val grokModel: String = "",
    val ready: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val googleSignInClient: GoogleSignInClient,
    private val driveBackupRepository: DriveBackupRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        combine(
            settings.telegramBotToken,
            settings.telegramChatId,
            settings.llmProvider,
            settings.anthropicApiKey,
            settings.anthropicModel,
            settings.nvidiaApiKey,
            settings.nvidiaModel,
        ) { arr -> arr.copyOf() },
        combine(
            settings.openaiApiKey,
            settings.openaiModel,
            settings.googleApiKey,
            settings.googleModel,
            settings.openrouterApiKey,
            settings.openrouterModel,
            settings.grokApiKey,
        ) { arr -> arr.copyOf() },
        settings.grokModel,
    ) { group1, group2, grokModel ->
        SettingsUiState(
            telegramBotToken = group1[0] ?: "",
            telegramChatId = group1[1] ?: "",
            llmProvider = group1[2] ?: "auto",
            anthropicApiKey = group1[3] ?: "",
            anthropicModel = group1[4] ?: "",
            nvidiaApiKey = group1[5] ?: "",
            nvidiaModel = group1[6] ?: "",
            openaiApiKey = group2[0] ?: "",
            openaiModel = group2[1] ?: "",
            googleApiKey = group2[2] ?: "",
            googleModel = group2[3] ?: "",
            openrouterApiKey = group2[4] ?: "",
            openrouterModel = group2[5] ?: "",
            grokApiKey = group2[6] ?: "",
            grokModel = grokModel ?: "",
            ready = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage

    val appAliases: StateFlow<Map<String, String>> = settings.appAliases
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _launchResult = MutableStateFlow<String?>(null)
    val launchResult: StateFlow<String?> = _launchResult

    fun clearSaveMessage() {
        _saveMessage.value = null
    }

    fun saveTelegram(botToken: String, chatId: String) {
        viewModelScope.launch {
            settings.setTelegramBotToken(botToken.trim())
            settings.setTelegramChatId(chatId.trim())
            _saveMessage.value = "Telegram settings saved"
        }
    }

    /**
     * Persist LLM settings. Blank key fields keep the previously saved key
     * (so a UI reset cannot wipe credentials). Use [clearProviderKey] to delete.
     */
    fun saveAllKeys(
        provider: String,
        nvidiaApiKey: String,
        nvidiaModel: String,
        anthropicApiKey: String,
        anthropicModel: String,
        openaiApiKey: String,
        openaiModel: String,
        googleApiKey: String,
        googleModel: String,
        openrouterApiKey: String,
        openrouterModel: String,
        grokApiKey: String,
        grokModel: String,
    ) {
        viewModelScope.launch {
            settings.setLlmProvider(provider.trim().lowercase().ifBlank { "auto" })
            if (nvidiaApiKey.isNotBlank()) settings.setNvidiaApiKey(nvidiaApiKey.trim())
            if (nvidiaModel.isNotBlank()) settings.setNvidiaModel(nvidiaModel.trim())
            if (anthropicApiKey.isNotBlank()) settings.setAnthropicApiKey(anthropicApiKey.trim())
            if (anthropicModel.isNotBlank()) settings.setAnthropicModel(anthropicModel.trim())
            if (openaiApiKey.isNotBlank()) settings.setOpenaiApiKey(openaiApiKey.trim())
            if (openaiModel.isNotBlank()) settings.setOpenaiModel(openaiModel.trim())
            if (googleApiKey.isNotBlank()) settings.setGoogleApiKey(googleApiKey.trim())
            if (googleModel.isNotBlank()) settings.setGoogleModel(googleModel.trim())
            if (openrouterApiKey.isNotBlank()) settings.setOpenrouterApiKey(openrouterApiKey.trim())
            if (openrouterModel.isNotBlank()) settings.setOpenrouterModel(openrouterModel.trim())
            if (grokApiKey.isNotBlank()) settings.setGrokApiKey(grokApiKey.trim())
            if (grokModel.isNotBlank()) settings.setGrokModel(grokModel.trim())

            val saved = listOfNotNull(
                nvidiaApiKey.takeIf { it.isNotBlank() }?.let { "NVIDIA" },
                anthropicApiKey.takeIf { it.isNotBlank() }?.let { "Anthropic" },
                openaiApiKey.takeIf { it.isNotBlank() }?.let { "OpenAI" },
                googleApiKey.takeIf { it.isNotBlank() }?.let { "Google" },
                openrouterApiKey.takeIf { it.isNotBlank() }?.let { "OpenRouter" },
                grokApiKey.takeIf { it.isNotBlank() }?.let { "Grok" },
            )
            val already = buildList {
                if (settings.nvidiaApiKey.first().orEmpty().isNotBlank()) add("NVIDIA")
                if (settings.anthropicApiKey.first().orEmpty().isNotBlank()) add("Anthropic")
                if (settings.openaiApiKey.first().orEmpty().isNotBlank()) add("OpenAI")
                if (settings.googleApiKey.first().orEmpty().isNotBlank()) add("Google")
                if (settings.openrouterApiKey.first().orEmpty().isNotBlank()) add("OpenRouter")
                if (settings.grokApiKey.first().orEmpty().isNotBlank()) add("Grok")
            }.distinct()
            _saveMessage.value = if (already.isEmpty() && saved.isEmpty()) {
                "Nothing saved — paste at least one API key."
            } else {
                "Keys saved for: ${already.joinToString()}. Chat can use them immediately."
            }
        }
    }

    fun clearProviderKey(provider: String) {
        viewModelScope.launch {
            when (provider) {
                "nvidia" -> settings.setNvidiaApiKey("")
                "anthropic" -> settings.setAnthropicApiKey("")
                "openai" -> settings.setOpenaiApiKey("")
                "google" -> settings.setGoogleApiKey("")
                "openrouter" -> settings.setOpenrouterApiKey("")
                "grok" -> settings.setGrokApiKey("")
            }
            _saveMessage.value = "Removed $provider key"
        }
    }

    fun addAppAlias(alias: String, packageName: String) {
        if (alias.isBlank() || packageName.isBlank()) return
        viewModelScope.launch {
            settings.setAppAliases(appAliases.value + (alias.trim().lowercase() to packageName.trim()))
        }
    }

    fun removeAppAlias(alias: String) {
        viewModelScope.launch {
            settings.setAppAliases(appAliases.value - alias)
        }
    }

    fun openApp(aliasOrPackage: String) {
        val packageName = appAliases.value[aliasOrPackage.trim().lowercase()] ?: aliasOrPackage.trim()
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            _launchResult.value = "Couldn't find an installed app for '$packageName'"
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        _launchResult.value = "Opened $packageName"
    }

    fun clearLaunchResult() {
        _launchResult.value = null
    }

    private val _driveAccountEmail = MutableStateFlow(driveBackupRepository.currentAccount()?.email)
    val driveAccountEmail: StateFlow<String?> = _driveAccountEmail

    private val _backupStatus = MutableStateFlow<DriveBackupStatus>(DriveBackupStatus.Idle)
    val backupStatus: StateFlow<DriveBackupStatus> = _backupStatus

    fun driveSignInIntent(): Intent = googleSignInClient.signInIntent

    fun handleDriveSignInResult(data: Intent?) {
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            _driveAccountEmail.value = account?.email
        } catch (e: ApiException) {
            _backupStatus.value = DriveBackupStatus.Failure("Sign-in failed: ${e.message}")
        }
    }

    fun driveSignOut() {
        googleSignInClient.signOut().addOnCompleteListener { _driveAccountEmail.value = null }
    }

    fun backupNow() {
        viewModelScope.launch {
            _backupStatus.value = DriveBackupStatus.Working
            runCatching { driveBackupRepository.backupNow() }
                .onSuccess { _backupStatus.value = DriveBackupStatus.Success("Backed up successfully.") }
                .onFailure { _backupStatus.value = DriveBackupStatus.Failure(it.message ?: "Backup failed") }
        }
    }

    fun restoreLatestBackup() {
        viewModelScope.launch {
            _backupStatus.value = DriveBackupStatus.Working
            runCatching { driveBackupRepository.restoreLatest() }
                .onSuccess { count -> _backupStatus.value = DriveBackupStatus.Success("Restored $count records.") }
                .onFailure { _backupStatus.value = DriveBackupStatus.Failure(it.message ?: "Restore failed") }
        }
    }
}
