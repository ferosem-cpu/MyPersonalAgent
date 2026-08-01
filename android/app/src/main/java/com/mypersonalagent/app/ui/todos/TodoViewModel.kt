package com.mypersonalagent.app.ui.todos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mypersonalagent.app.data.local.TodoEntity
import com.mypersonalagent.app.data.repo.TodoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TodoViewModel @Inject constructor(
    private val repository: TodoRepository,
) : ViewModel() {

    val todos: StateFlow<List<TodoEntity>> = repository.todos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repository.refresh() }
                .onFailure { _error.value = it.message ?: "Failed to refresh" }
        }
    }

    fun addTodo(title: String, project: String, due: String?) {
        viewModelScope.launch {
            runCatching { repository.create(title, project, due) }
                .onFailure { _error.value = it.message ?: "Failed to create to-do" }
        }
    }

    fun completeTodo(id: String) {
        viewModelScope.launch {
            runCatching { repository.complete(id) }
                .onFailure { _error.value = it.message ?: "Failed to complete to-do" }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
