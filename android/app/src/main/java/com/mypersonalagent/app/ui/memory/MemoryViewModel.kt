package com.mypersonalagent.app.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mypersonalagent.app.data.remote.NoteDto
import com.mypersonalagent.app.data.repo.MemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

const val SHOPPING_TAG = "shopping"

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val repository: MemoryRepository,
) : ViewModel() {

    private val _notes = MutableStateFlow<List<NoteDto>>(emptyList())
    val notes: StateFlow<List<NoteDto>> = _notes.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Shopping list is just notes tagged "shopping" (PLAN_V2 Task 6.3) - syncs for
     * free via the existing memory endpoints, no separate storage needed. */
    val shoppingItems: StateFlow<List<NoteDto>> = _notes
        .map { list -> list.filter { SHOPPING_TAG in it.tags } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            runCatching { repository.list() }
                .onSuccess { _notes.value = it; _error.value = null }
                .onFailure { _error.value = it.message ?: "Failed to load memory" }
            _loading.value = false
        }
    }

    fun search(query: String) {
        if (query.isBlank()) {
            refresh()
            return
        }
        viewModelScope.launch {
            _loading.value = true
            runCatching { repository.recall(query) }
                .onSuccess { _notes.value = it; _error.value = null }
                .onFailure { _error.value = it.message ?: "Failed to search memory" }
            _loading.value = false
        }
    }

    fun remember(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            runCatching { repository.remember(text) }
                .onSuccess { refresh() }
                .onFailure { _error.value = it.message ?: "Failed to save note" }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
