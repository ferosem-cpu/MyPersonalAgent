package com.mypersonalagent.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mypersonalagent.app.data.repo.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatMessage(val text: String, val fromUser: Boolean)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun send(text: String) {
        if (text.isBlank() || _sending.value) return
        _messages.value = _messages.value + ChatMessage(text, fromUser = true)
        _sending.value = true
        viewModelScope.launch {
            runCatching { repository.send(text) }
                .onSuccess { reply ->
                    _messages.value = _messages.value + ChatMessage(reply, fromUser = false)
                    _error.value = null
                }
                .onFailure { _error.value = it.message ?: "Failed to reach the agent" }
            _sending.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }
}
