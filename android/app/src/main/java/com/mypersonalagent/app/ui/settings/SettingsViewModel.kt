package com.mypersonalagent.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mypersonalagent.app.data.remote.ApiService
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

data class SettingsUiState(val serverUrl: String = "", val apiToken: String = "")

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
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(settings.serverUrl, settings.apiToken) { url, token ->
        SettingsUiState(serverUrl = url ?: "", apiToken = token ?: "")
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
}
