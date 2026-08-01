package com.mypersonalagent.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TodoDto(
    val id: String? = null,
    val title: String,
    val project: String = "",
    val due: String? = null,
    val recurrence: String? = null,
    @SerialName("remind_before_min") val remindBeforeMin: Int = 30,
    val status: String = "open",
    @SerialName("snooze_until") val snoozeUntil: String? = null,
    val created: String? = null,
    val completed: String? = null,
    @SerialName("last_reminded") val lastReminded: String? = null,
    @SerialName("escalation_step") val escalationStep: Int = 0,
    val updated: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class EntryDto(
    val id: String? = null,
    val ts: String? = null,
    val title: String,
    val desc: String = "",
    val project: String = "",
    val minutes: Int = 0,
    val updated: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class NoteDto(
    val id: String? = null,
    val text: String,
    val tags: List<String> = emptyList(),
    val created: String? = null,
    val updated: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class ContactDto(
    val id: String? = null,
    val name: String,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
    val email: String? = null,
    @SerialName("telegram_user_id") val telegramUserId: String? = null,
    val created: String? = null,
    val updated: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class ChatRequestDto(
    val message: String,
)

@Serializable
data class ChatResponseDto(
    val reply: String,
)

@Serializable
data class HealthDto(
    val status: String,
    val version: String = "",
)

@Serializable
data class SyncRequest(
    @SerialName("last_sync") val lastSync: String? = null,
    val changes: Map<String, List<kotlinx.serialization.json.JsonElement>> = emptyMap(),
)

@Serializable
data class SyncResponse(
    @SerialName("server_time") val serverTime: String,
    val applied: Map<String, List<String>> = emptyMap(),
    val rejected: Map<String, List<kotlinx.serialization.json.JsonElement>> = emptyMap(),
    val changes: Map<String, List<kotlinx.serialization.json.JsonElement>> = emptyMap(),
)
