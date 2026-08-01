package com.mypersonalagent.app.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mypersonalagent.app.data.local.EntryEntity
import com.mypersonalagent.app.data.repo.EntryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LogViewModel @Inject constructor(
    private val repository: EntryRepository,
) : ViewModel() {

    val entries: StateFlow<List<EntryEntity>> = repository.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            runCatching { repository.refresh() }
                .onFailure { _error.value = it.message ?: "Failed to load work log" }
            _loading.value = false
        }
    }

    fun logWork(title: String, desc: String, project: String, minutes: Int) {
        viewModelScope.launch {
            runCatching { repository.logWork(title, desc, project, minutes) }
                .onSuccess { _status.value = "Logged: $title" }
                .onFailure { _status.value = it.message ?: "Failed to log entry" }
        }
    }

    fun clearStatus() {
        _status.value = null
    }

    fun clearError() {
        _error.value = null
    }
}
