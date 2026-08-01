package com.mypersonalagent.app.data.repo

import com.mypersonalagent.app.data.remote.ApiService
import com.mypersonalagent.app.data.remote.ChatRequestDto
import javax.inject.Inject
import javax.inject.Singleton

/** Online-only. Server restricts the LLM's tools to data operations only (see routes_chat.py). */
@Singleton
class ChatRepository @Inject constructor(
    private val api: ApiService,
) {
    suspend fun send(message: String): String = api.chat(ChatRequestDto(message)).reply
}
