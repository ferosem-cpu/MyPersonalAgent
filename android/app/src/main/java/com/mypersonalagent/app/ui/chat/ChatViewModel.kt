package com.mypersonalagent.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mypersonalagent.app.data.local.AssistantEntity
import com.mypersonalagent.app.data.local.ChatMessageEntity
import com.mypersonalagent.app.data.repo.AssistantRepository
import com.mypersonalagent.app.data.repo.ChatRepository
import com.mypersonalagent.app.data.repo.ChatTurn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chat: ChatRepository,
    private val assistants: AssistantRepository,
) : ViewModel() {

    val threadId: String = savedStateHandle.get<String>("assistantId").orEmpty()

    private val _bot = MutableStateFlow<AssistantEntity?>(null)
    val bot: StateFlow<AssistantEntity?> = _bot.asStateFlow()

    val messages: StateFlow<List<ChatMessageEntity>> = assistants.messages(threadId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            assistants.ensureSeeded()
            _bot.value = assistants.get(threadId)
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || _sending.value || threadId.isBlank()) return
        _sending.value = true
        _error.value = null
        viewModelScope.launch {
            val current = assistants.get(threadId)
            if (current == null) {
                _sending.value = false
                return@launch
            }
            assistants.addMessage(threadId, "user", trimmed, speakerName = "You")
            runCatching {
                if (current.isGroup) runGroup(current, trimmed) else runSolo(current, trimmed)
            }.onFailure { _error.value = it.message ?: "Failed to reach the model" }
            _sending.value = false
        }
    }

    fun deleteAssistant(onDone: () -> Unit) {
        viewModelScope.launch {
            if (threadId.isNotBlank()) assistants.delete(threadId)
            onDone()
        }
    }

    fun clearError() { _error.value = null }

    private suspend fun runSolo(bot: AssistantEntity, userText: String) {
        val reply = chat.send(userText, buildPersona(bot), visibleTurns(threadId))
        assistants.addMessage(threadId, "assistant", reply.text, speakerId = bot.id, speakerName = bot.name)
    }

    private suspend fun runGroup(room: AssistantEntity, userText: String) {
        var ids = assistants.memberIds(room)
        if (ids.isEmpty()) ids = assistants.list().filter { !it.isGroup }.map { it.id }.take(4)
        val members = ids.mapNotNull { assistants.get(it) }.take(4)
        if (members.isEmpty()) {
            assistants.addMessage(threadId, "assistant", "No teammates in this room yet.", speakerName = room.name)
            return
        }
        val shared = visibleTurns(threadId)
        for (member in members) {
            val persona = buildPersona(member) +
                "\nYou are in a group room with: ${members.joinToString { it.name }}. " +
                "Reply only if you have a useful next step in your lane. If not, reply with exactly PASS."
            val reply = chat.send(userText, persona, shared)
            val text = reply.text.trim()
            if (text.isBlank() || text.equals("PASS", true) || text.startsWith("PASS")) continue
            assistants.addMessage(threadId, "assistant", text, speakerId = member.id, speakerName = member.name)
        }
    }

    private suspend fun visibleTurns(id: String): List<ChatTurn> {
        return assistants.listThread(id)
            .filter { (it.role == "user" || it.role == "assistant") && it.kind == "text" }
            .takeLast(16)
            .map { ChatTurn(it.role, it.content) }
    }

    private fun buildPersona(bot: AssistantEntity): String {
        return "You are ${bot.name}, ${bot.title}.\n${bot.instructions}\n" +
            "You share one workspace on this phone: todos, notes, contacts, files. " +
            "Use tools when work should persist. Be concise. Talk like a teammate."
    }
}
