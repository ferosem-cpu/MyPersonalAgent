package com.mypersonalagent.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mypersonalagent.app.data.remote.ApiService
import com.mypersonalagent.app.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(settings.serverUrl, settings.apiToken) { url, token ->
        SettingsUiState(serverUrl = url ?: "", apiToken = token ?: "")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private val _testResult = MutableStateFlow<ConnectionTestResult>(ConnectionTestResult.Idle)
    val testResult: StateFlow<ConnectionTestResult> = _testResult

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
}
