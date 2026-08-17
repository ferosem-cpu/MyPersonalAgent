package com.mypersonalagent.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.common.api.ApiException
import com.mypersonalagent.app.data.remote.ApiService
import com.mypersonalagent.app.data.repo.DriveBackupRepository
import com.mypersonalagent.app.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val serverUrl: String = "",
    val apiToken: String = "",
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
)

sealed interface ConnectionTestResult {
    object Idle : ConnectionTestResult
    object Testing : ConnectionTestResult
    object Success : ConnectionTestResult
    data class Failure(val message: String) : ConnectionTestResult
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val api: ApiService,
    private val googleSignInClient: GoogleSignInClient,
    private val driveBackupRepository: DriveBackupRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        combine(
            settings.serverUrl, settings.apiToken, settings.telegramBotToken, settings.telegramChatId,
            settings.llmProvider, settings.anthropicApiKey, settings.anthropicModel,
        ) { arr -> arr },
        combine(
            settings.nvidiaApiKey, settings.nvidiaModel, settings.openaiApiKey, settings.openaiModel,
            settings.googleApiKey, settings.googleModel, settings.openrouterApiKey,
        ) { arr -> arr },
        combine(
            settings.openrouterModel, settings.grokApiKey, settings.grokModel,
        ) { arr -> arr },
    ) { group1, group2, group3 ->
        SettingsUiState(
            serverUrl = group1[0] ?: "", apiToken = group1[1] ?: "",
            telegramBotToken = group1[2] ?: "", telegramChatId = group1[3] ?: "",
            llmProvider = group1[4] ?: "auto",
            anthropicApiKey = group1[5] ?: "", anthropicModel = group1[6] ?: "",
            nvidiaApiKey = group2[0] ?: "", nvidiaModel = group2[1] ?: "",
            openaiApiKey = group2[2] ?: "", openaiModel = group2[3] ?: "",
            googleApiKey = group2[4] ?: "", googleModel = group2[5] ?: "",
            openrouterApiKey = group2[6] ?: "", openrouterModel = group3[0] ?: "",
            grokApiKey = group3[1] ?: "", grokModel = group3[2] ?: "",
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private val _testResult = MutableStateFlow<ConnectionTestResult>(ConnectionTestResult.Idle)
    val testResult: StateFlow<ConnectionTestResult> = _testResult

    val appAliases: StateFlow<Map<String, String>> = settings.appAliases
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _launchResult = MutableStateFlow<String?>(null)
    val launchResult: StateFlow<String?> = _launchResult

    fun save(serverUrl: String, apiToken: String) {
        viewModelScope.launch {
            settings.setServerUrl(serverUrl.trimEnd('/'))
            settings.setApiToken(apiToken)
        }
    }

    fun saveTelegram(botToken: String, chatId: String) {
        viewModelScope.launch {
            settings.setTelegramBotToken(botToken.trim())
            settings.setTelegramChatId(chatId.trim())
        }
    }

    fun saveLlmProvider(provider: String) {
        viewModelScope.launch {
            settings.setLlmProvider(provider.trim().lowercase())
        }
    }

    fun saveAnthropic(apiKey: String, model: String) {
        viewModelScope.launch {
            settings.setAnthropicApiKey(apiKey.trim())
            settings.setAnthropicModel(model.trim())
        }
    }

    fun saveNvidia(apiKey: String, model: String) {
        viewModelScope.launch {
            settings.setNvidiaApiKey(apiKey.trim())
            settings.setNvidiaModel(model.trim())
        }
    }

    fun saveOpenai(apiKey: String, model: String) {
        viewModelScope.launch {
            settings.setOpenaiApiKey(apiKey.trim())
            settings.setOpenaiModel(model.trim())
        }
    }

    fun saveGoogle(apiKey: String, model: String) {
        viewModelScope.launch {
            settings.setGoogleApiKey(apiKey.trim())
            settings.setGoogleModel(model.trim())
        }
    }

    fun saveOpenrouter(apiKey: String, model: String) {
        viewModelScope.launch {
            settings.setOpenrouterApiKey(apiKey.trim())
            settings.setOpenrouterModel(model.trim())
        }
    }

    fun saveGrok(apiKey: String, model: String) {
        viewModelScope.launch {
            settings.setGrokApiKey(apiKey.trim())
            settings.setGrokModel(model.trim())
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _testResult.value = ConnectionTestResult.Testing
            runCatching { api.health() }
                .onSuccess { _testResult.value = ConnectionTestResult.Success }
                .onFailure { _testResult.value = ConnectionTestResult.Failure(it.message ?: "Connection failed") }
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

    /** Purely local, no server round-trip (PLAN_V2 Task 6.2) - resolves the alias to a
     * package name and launches it directly via PackageManager. */
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

    // --- Google Drive backup (Phase E) ---

    private val _driveAccountEmail = MutableStateFlow(driveBackupRepository.currentAccount()?.email)
    val driveAccountEmail: StateFlow<String?> = _driveAccountEmail

    private val _backupStatus = MutableStateFlow<DriveBackupStatus>(DriveBackupStatus.Idle)
    val backupStatus: StateFlow<DriveBackupStatus> = _backupStatus

    fun driveSignInIntent(): Intent = googleSignInClient.signInIntent

    /** Call from the Activity's sign-in ActivityResultLauncher callback with the returned Intent. */
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
