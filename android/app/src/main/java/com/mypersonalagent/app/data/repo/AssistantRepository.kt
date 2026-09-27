package com.mypersonalagent.app.data.repo

import com.mypersonalagent.app.data.local.AssistantDao
import com.mypersonalagent.app.data.local.AssistantEntity
import com.mypersonalagent.app.data.local.ChatMessageDao
import com.mypersonalagent.app.data.local.ChatMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class AssistantPreset(
    val name: String,
    val title: String,
    val instructions: String,
    val colorHex: String,
    val shape: String,
    val emoji: String,
)

@Singleton
class AssistantRepository @Inject constructor(
    private val assistantDao: AssistantDao,
    private val messageDao: ChatMessageDao,
) {
    val assistants: Flow<List<AssistantEntity>> = assistantDao.observeAll()

    fun messages(threadId: String): Flow<List<ChatMessageEntity>> = messageDao.observeThread(threadId)

    suspend fun get(id: String): AssistantEntity? = assistantDao.get(id)

    suspend fun list(): List<AssistantEntity> = assistantDao.list()

    suspend fun ensureSeeded() {
        if (assistantDao.count() > 0) return
        val now = Instant.now().toString()
        val seeds = defaultPresets().mapIndexed { index, preset ->
            AssistantEntity(
                id = UUID.randomUUID().toString(),
                name = preset.name,
                title = preset.title,
                instructions = preset.instructions,
                colorHex = preset.colorHex,
                shape = preset.shape,
                emoji = preset.emoji,
                isGroup = false,
                memberIdsJson = "[]",
                pinned = index == 0,
                hidden = false,
                created = now,
                updated = now,
                lastActive = now,
            )
        }
        assistantDao.upsertAll(seeds)
        seeds.forEach { bot ->
            messageDao.insert(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    threadId = bot.id,
                    role = "assistant",
                    content = "I'm ${bot.name}. ${bot.title}. Message me like a teammate.",
                    speakerId = bot.id,
                    speakerName = bot.name,
                    created = now,
                ),
            )
        }
        val roomId = UUID.randomUUID().toString()
        val memberJson = buildJsonArray {
            seeds.take(4).forEach { add(JsonPrimitive(it.id)) }
        }.toString()
        assistantDao.upsert(
            AssistantEntity(
                id = roomId,
                name = "War Room",
                title = "Shared channel for the crew",
                instructions = "Group room. Specialists speak in turn.",
                colorHex = "#111318",
                shape = "hex",
                emoji = "W",
                isGroup = true,
                memberIdsJson = memberJson,
                pinned = true,
                hidden = false,
                created = now,
                updated = now,
                lastActive = now,
            ),
        )
        messageDao.insert(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                threadId = roomId,
                role = "assistant",
                content = "War Room is live. Drop a job and the crew will pick it up.",
                speakerName = "War Room",
                created = now,
            ),
        )
    }

    suspend fun create(
        name: String,
        title: String,
        instructions: String,
        colorHex: String,
        shape: String = "round",
    ): AssistantEntity {
        val now = Instant.now().toString()
        val entity = AssistantEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            title = title.trim(),
            instructions = instructions.trim(),
            colorHex = colorHex,
            shape = shape,
            emoji = name.trim().take(1).uppercase(),
            isGroup = false,
            memberIdsJson = "[]",
            pinned = false,
            hidden = false,
            created = now,
            updated = now,
            lastActive = now,
        )
        assistantDao.upsert(entity)
        messageDao.insert(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                threadId = entity.id,
                role = "assistant",
                content = "I'm ${entity.name}. ${entity.title.ifBlank { \"Ready when you are.\" }}",
                speakerId = entity.id,
                speakerName = entity.name,
                created = now,
            ),
        )
        return entity
    }

    suspend fun delete(id: String) {
        messageDao.clearThread(id)
        assistantDao.delete(id)
    }

    suspend fun touch(id: String) {
        assistantDao.touch(id, Instant.now().toString())
    }

    suspend fun addMessage(
        threadId: String,
        role: String,
        content: String,
        speakerId: String? = null,
        speakerName: String? = null,
        kind: String = "text",
    ): ChatMessageEntity {
        val entity = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            threadId = threadId,
            role = role,
            content = content,
            speakerId = speakerId,
            speakerName = speakerName,
            kind = kind,
            created = Instant.now().toString(),
        )
        messageDao.insert(entity)
        touch(threadId)
        return entity
    }

    suspend fun listThread(threadId: String): List<ChatMessageEntity> = messageDao.listThread(threadId)

    suspend fun memberIds(bot: AssistantEntity): List<String> {
        return runCatching {
            val el = Json.parseToJsonElement(bot.memberIdsJson)
            (el as? JsonArray)?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    companion object {
        fun defaultPresets(): List<AssistantPreset> = listOf(
            AssistantPreset(
                name = "Chief of Staff",
                title = "Routes work and keeps one thread of status",
                instructions = "You are Chief of Staff. Be concise. Route tasks, summarize status, create todos when work is committed. Ask only if a decision is blocked.",
                colorHex = "#7C5CFF",
                shape = "round",
                emoji = "C",
            ),
            AssistantPreset(
                name = "Researcher",
                title = "Digs, compares, and briefs",
                instructions = "You are Researcher. Structure findings. Separate facts from guesses. End with 3 next moves.",
                colorHex = "#4DA3FF",
                shape = "drop",
                emoji = "R",
            ),
            AssistantPreset(
                name = "Writer",
                title = "Drafts in the user's voice",
                instructions = "You are Writer. Draft emails, posts, and proposals. Never claim you sent anything.",
                colorHex = "#FF7A59",
                shape = "round",
                emoji = "W",
            ),
            AssistantPreset(
                name = "Ops",
                title = "Todos, reminders, follow-ups",
                instructions = "You are Ops. Prefer tools over chatter. Create, list, and complete todos. Confirm what changed in one line.",
                colorHex = "#3DDC97",
                shape = "hex",
                emoji = "O",
            ),
            AssistantPreset(
                name = "Memory",
                title = "People, prefs, standing facts",
                instructions = "You are Memory. Save durable facts with remember. Recall before asking the user to repeat themselves.",
                colorHex = "#FFB020",
                shape = "drop",
                emoji = "M",
            ),
        )
    }
}
