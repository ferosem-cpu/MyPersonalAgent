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

const val DEFAULT_LLM_BASE_URL = "https://freellmapi-ferose.duckdns.org/v1"
const val DEFAULT_LLM_MODEL = "gpt-4o-mini"

sealed interface DriveBackupStatus {
    object Idle : DriveBackupStatus
    object Working : DriveBackupStatus
    data class Success(val message: String) : DriveBackupStatus
    data class Failure(val message: String) : DriveBackupStatus
}

data class SettingsUiState(
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val customApiKey: String = "",
    val customBaseUrl: String = DEFAULT_LLM_BASE_URL,
    val customModel: String = DEFAULT_LLM_MODEL,
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
        settings.telegramBotToken,
        settings.telegramChatId,
        settings.customApiKey,
        settings.customBaseUrl,
        settings.customModel,
    ) { botToken, chatId, apiKey, baseUrl, model ->
        SettingsUiState(
            telegramBotToken = botToken.orEmpty(),
            telegramChatId = chatId.orEmpty(),
            customApiKey = apiKey.orEmpty(),
            customBaseUrl = baseUrl?.trim()?.ifBlank { null } ?: DEFAULT_LLM_BASE_URL,
            customModel = model?.trim()?.ifBlank { null } ?: DEFAULT_LLM_MODEL,
            ready = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val _saveMessage = MutableStateFlow<String?>(null)
    val saveMessage: StateFlow<String?> = _saveMessage

    val appAliases: StateFlow<Map<String, String>> = settings.appAliases
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val driveFolderUri: StateFlow<String?> = settings.driveFolderUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
     * Persist the single OpenAI-compatible gateway. Blank key keeps the
     * previously saved key so a UI reset cannot wipe credentials.
     */
    fun saveGateway(apiKey: String, baseUrl: String, model: String) {
        viewModelScope.launch {
            settings.setLlmProvider("custom")
            if (apiKey.isNotBlank()) settings.setCustomApiKey(apiKey.trim())
            val url = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_LLM_BASE_URL }
            settings.setCustomBaseUrl(url)
            settings.setCustomModel(model.trim().ifBlank { DEFAULT_LLM_MODEL })

            val storedKey = settings.customApiKey.first().orEmpty()
            _saveMessage.value = if (storedKey.isBlank()) {
                "Nothing saved — paste your FreeLLM / Reeller API key."
            } else {
                "Gateway saved. Chat will call $url — no rebuild needed when you change models or backends on the gateway."
            }
        }
    }

    fun clearGatewayKey() {
        viewModelScope.launch {
            settings.setCustomApiKey("")
            _saveMessage.value = "Removed API key"
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

    fun setDriveFolderUri(uri: String) {
        viewModelScope.launch { settings.setDriveFolderUri(uri) }
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
