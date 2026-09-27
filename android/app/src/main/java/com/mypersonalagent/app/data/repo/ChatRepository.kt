package com.mypersonalagent.app.data.repo

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private const val MAX_TOOL_ROUNDS = 6
private const val DEFAULT_GATEWAY_URL = "https://freellmapi-ferose.duckdns.org/v1"
private const val DEFAULT_GATEWAY_MODEL = "gpt-4o-mini"

private const val SYSTEM_PROMPT = """
You are the on-device assistant for MyPersonalAgent, a personal productivity app. You can
create and manage the user's todos, log work entries, save/recall notes, save contacts,
list files they dropped into the app, and open installed apps using saved aliases.
Everything you do stays on this phone. Be concise. When you take an action, confirm briefly
what you did. When you need an id for complete_todo or snooze_todo, call list_todos first.
Dropped files are stored on the phone and copied into the user's Google Drive folder
(Pictures / Documents / Code / Others) when they have chosen that folder.
"""

data class ProviderConfig(
    val name: String,
    val apiKey: String,
    val model: String,
    val endpointUrl: String,
    val type: String,
)

data class ChatReply(
    val text: String,
    val provider: String? = null,
)

@Singleton
class ChatRepository @Inject constructor(
    @Named("anthropic") private val httpClient: OkHttpClient,
    @ApplicationContext private val appContext: Context,
    private val settings: SettingsRepository,
    private val todoRepository: TodoRepository,
    private val entryRepository: EntryRepository,
    private val memoryRepository: MemoryRepository,
    private val contactsRepository: ContactsRepository,
    private val fileInbox: FileInboxRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val history = mutableListOf<JsonObject>()

    suspend fun send(message: String): ChatReply {
        val configuredProviders = getConfiguredProviders()
        if (configuredProviders.isEmpty()) {
            return ChatReply(
                "No gateway saved yet. Open Settings, paste your FreeLLM endpoint and API key, tap Save gateway, then try again.",
            )
        }
        val snapshot = history.toList()
        var lastError: Exception? = null
        for (provider in configuredProviders) {
            try {
                history.clear()
                history.addAll(snapshot)
                val reply = executeTurn(provider, message).trim()
                if (reply.isBlank() || reply == "(no reply)") {
                    throw IllegalStateException("${provider.name} returned an empty reply")
                }
                return ChatReply(reply, provider.name)
            } catch (e: Exception) {
                history.clear()
                history.addAll(snapshot)
                lastError = e
            }
        }
        val detail = lastError?.message?.take(400) ?: "Unknown error"
        return ChatReply("Gateway call failed. Last error: $detail")
    }

    private fun completionsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    private suspend fun getConfiguredProviders(): List<ProviderConfig> {
        val key = settings.customApiKey.first()?.trim().orEmpty()
        if (key.isBlank()) return emptyList()
        val base = settings.customBaseUrl.first()?.trim()?.trimEnd('/')?.ifBlank { null } ?: DEFAULT_GATEWAY_URL
        val model = settings.customModel.first()?.trim()?.ifBlank { null } ?: DEFAULT_GATEWAY_MODEL
        return listOf(
            ProviderConfig(
                name = "gateway",
                apiKey = key,
                model = model,
                endpointUrl = completionsUrl(base),
                type = "openai",
            ),
        )
    }

    private suspend fun executeTurn(provider: ProviderConfig, userMessage: String): String {
        return executeTurnOpenAI(provider, userMessage)
    }

    private suspend fun executeTurnOpenAI(provider: ProviderConfig, userMessage: String): String {
        val messages = mutableListOf<JsonObject>()
        messages.add(buildJsonObject { put("role", "system"); put("content", SYSTEM_PROMPT.trim()) })
        history.forEach { item ->
            val role = item["role"]?.jsonPrimitive?.contentOrNull ?: "user"
            val content = item["content"]
            if (content is JsonPrimitive) {
                messages.add(buildJsonObject { put("role", role); put("content", content.content) })
            } else if (content is JsonArray) {
                val text = content.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    obj["text"]?.jsonPrimitive?.contentOrNull
                }.joinToString("\n")
                if (text.isNotBlank()) {
                    messages.add(buildJsonObject { put("role", role); put("content", text) })
                }
            }
        }
        messages.add(buildJsonObject { put("role", "user"); put("content", userMessage) })
        history.add(buildJsonObject { put("role", "user"); put("content", userMessage) })

        repeat(MAX_TOOL_ROUNDS) {
            val response = callOpenAI(provider, messages)
            val errorObj = response["error"]?.jsonObject
            if (errorObj != null) {
                val msg = errorObj["message"]?.jsonPrimitive?.contentOrNull ?: errorObj.toString()
                error("${provider.name} API error: $msg")
            }
            val choice = response["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: error("Invalid ${provider.name} response (no choices)")
            val messageObj = choice["message"]?.jsonObject ?: error("Invalid ${provider.name} choice message")
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            messages.add(messageObj)
            val textReply = messageObj["content"]?.let { el ->
                when (el) {
                    is JsonPrimitive -> el.contentOrNull
                    is JsonArray -> el.mapNotNull { part ->
                        (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                    }.joinToString("").ifBlank { null }
                    else -> null
                }
            }
            val toolCalls = messageObj["tool_calls"]?.jsonArray
            if (toolCalls.isNullOrEmpty() || finishReason != "tool_calls") {
                val finalReply = textReply?.trim().orEmpty()
                if (finalReply.isBlank()) error("${provider.name} returned an empty reply")
                history.add(buildJsonObject { put("role", "assistant"); put("content", finalReply) })
                return finalReply
            }
            toolCalls.mapNotNull { it as? JsonObject }.forEach { call ->
                val callId = call["id"]?.jsonPrimitive?.content ?: ""
                val funcObj = call["function"]?.jsonObject ?: JsonObject(emptyMap())
                val name = funcObj["name"]?.jsonPrimitive?.content ?: ""
                val argsRaw = funcObj["arguments"]?.jsonPrimitive?.content ?: "{}"
                val argsJson = runCatching { json.parseToJsonElement(argsRaw).jsonObject }
                    .getOrDefault(JsonObject(emptyMap()))
                val resultText = runCatching { executeTool(name, argsJson) }
                    .getOrElse { "Error running $name: ${it.message}" }
                messages.add(
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", callId)
                        put("content", resultText)
                    },
                )
            }
        }
        error("Reached maximum tool execution rounds.")
    }

    fun clearHistory() { history.clear() }

    private suspend fun executeTool(name: String, input: JsonObject): String = when (name) {
        "add_todo" -> {
            val title = input["title"]?.jsonPrimitive?.contentOrNull ?: return "Missing 'title'."
            todoRepository.create(
                title = title,
                project = input["project"]?.jsonPrimitive?.contentOrNull ?: "",
                due = input["due"]?.jsonPrimitive?.contentOrNull,
            )
            "Added todo: $title"
        }
        "complete_todo" -> {
            val id = input["id"]?.jsonPrimitive?.contentOrNull ?: return "Missing 'id'."
            todoRepository.complete(id)
            "Marked complete."
        }
        "list_todos" -> {
            val status = input["status"]?.jsonPrimitive?.contentOrNull ?: "open"
            val todos = todoRepository.todos.first().filter { status == "all" || it.status == status }
            if (todos.isEmpty()) "No todos." else todos.joinToString("\n") { t ->
                "- [id=${t.id}] ${t.title}" + (t.due?.let { " (due $it)" } ?: "")
            }
        }
        "log_work" -> {
            val title = input["title"]?.jsonPrimitive?.contentOrNull ?: return "Missing 'title'."
            entryRepository.logWork(
                title = title,
                desc = input["desc"]?.jsonPrimitive?.contentOrNull ?: "",
                project = input["project"]?.jsonPrimitive?.contentOrNull ?: "",
                minutes = input["minutes"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0,
            )
            "Logged work entry: $title"
        }
        "remember" -> {
            val text = input["text"]?.jsonPrimitive?.contentOrNull ?: return "Missing 'text'."
            memoryRepository.remember(text)
            "Saved to memory."
        }
        "recall" -> {
            val query = input["query"]?.jsonPrimitive?.contentOrNull ?: ""
            val notes = memoryRepository.recall(query)
            if (notes.isEmpty()) "No matching notes." else notes.joinToString("\n") { "- ${it.text}" }
        }
        "snooze_todo" -> {
            val id = input["id"]?.jsonPrimitive?.contentOrNull ?: return "Missing 'id'."
            val until = input["until"]?.jsonPrimitive?.contentOrNull ?: return "Missing 'until' (ISO-8601 datetime)."
            todoRepository.snooze(id, until)
            "Snoozed until $until."
        }
        "add_contact" -> {
            val name = input["name"]?.jsonPrimitive?.contentOrNull ?: return "Missing 'name'."
            val saved = contactsRepository.save(
                name = name,
                phone = input["phone"]?.jsonPrimitive?.contentOrNull,
                email = input["email"]?.jsonPrimitive?.contentOrNull,
            )
            "Saved contact ${saved.name}."
        }
        "list_contacts" -> {
            val query = input["query"]?.jsonPrimitive?.contentOrNull
            val contacts = contactsRepository.list(query)
            if (contacts.isEmpty()) "No contacts." else contacts.joinToString("\n") { c ->
                buildString {
                    append("- ${c.name}")
                    c.phoneNumber?.let { append(" · $it") }
                    c.email?.let { append(" · $it") }
                }
            }
        }
        "list_files" -> {
            val files = fileInbox.files.first()
            if (files.isEmpty()) "No files dropped yet."
            else files.joinToString("\n") { "- ${it.displayName} (${it.category})" }
        }
        "open_app" -> {
            val alias = input["name"]?.jsonPrimitive?.contentOrNull ?: return "Missing 'name'."
            val aliases = settings.appAliases.first()
            val packageName = aliases[alias.trim().lowercase()] ?: alias.trim()
            val intent = appContext.packageManager.getLaunchIntentForPackage(packageName)
                ?: return "Couldn't find an installed app for '$packageName'. Save an alias in Settings first."
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            "Opened $packageName."
        }
        else -> "Unknown tool: $name"
    }

    private suspend fun callOpenAI(provider: ProviderConfig, messages: List<JsonObject>): JsonObject {
        val body = buildJsonObject {
            put("model", provider.model)
            put("messages", JsonArray(messages))
            put("tools", OPENAI_TOOLS)
        }
        val request = Request.Builder()
            .url(provider.endpointUrl)
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: error("Empty response from ${provider.name} API")
                if (!resp.isSuccessful) error("${provider.name} API error ${resp.code}: $bodyStr")
                json.parseToJsonElement(bodyStr).jsonObject
            }
        }
    }

    companion object {
        private val OPENAI_TOOLS = buildJsonArray {
            add(openAiTool("add_todo", "Create a new todo.") {
                stringProp("title", "The todo's title.", required = true)
                stringProp("project", "Optional project/category name.")
                stringProp("due", "Optional ISO-8601 due date/time.")
            })
            add(openAiTool("complete_todo", "Mark a todo complete by its id. Call list_todos first if you don't have the id.") {
                stringProp("id", "The todo's id.", required = true)
            })
            add(openAiTool("list_todos", "List todos, optionally filtered by status.") {
                stringProp("status", "One of: open, done, snoozed, all. Defaults to open.")
            })
            add(openAiTool("log_work", "Log a completed work entry.") {
                stringProp("title", "What was done.", required = true)
                stringProp("desc", "Optional longer description.")
                stringProp("project", "Optional project/category name.")
                numberProp("minutes", "Minutes spent.")
            })
            add(openAiTool("remember", "Save a note to memory.") {
                stringProp("text", "The note text.", required = true)
            })
            add(openAiTool("recall", "Search saved notes.") {
                stringProp("query", "Search text.")
            })
            add(openAiTool("snooze_todo", "Snooze a todo until a datetime. Call list_todos first for the id.") {
                stringProp("id", "The todo's id.", required = true)
                stringProp("until", "ISO-8601 datetime to snooze until.", required = true)
            })
            add(openAiTool("add_contact", "Save a contact.") {
                stringProp("name", "Full name.", required = true)
                stringProp("phone", "Phone number.")
                stringProp("email", "Email address.")
            })
            add(openAiTool("list_contacts", "List or search saved contacts.") {
                stringProp("query", "Optional name/phone/email filter.")
            })
            add(openAiTool("list_files", "List files the user dropped into the app.") {}
            )
            add(openAiTool("open_app", "Open an installed app by alias or package name.") {
                stringProp("name", "Alias or package name.", required = true)
            })
        }

        private fun openAiTool(name: String, description: String, buildProps: PropsBuilder.() -> Unit): JsonObject {
            val builder = PropsBuilder().apply(buildProps)
            return buildJsonObject {
                put("type", "function")
                put(
                    "function",
                    buildJsonObject {
                        put("name", name)
                        put("description", description)
                        put(
                            "parameters",
                            buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject { builder.properties.forEach { (k, v) -> put(k, v) } })
                                put("required", buildJsonArray { builder.required.forEach { add(JsonPrimitive(it)) } })
                            },
                        )
                    },
                )
            }
        }
    }

    private class PropsBuilder {
        val properties = mutableMapOf<String, JsonObject>()
        val required = mutableListOf<String>()
        fun stringProp(name: String, description: String, required: Boolean = false) {
            properties[name] = buildJsonObject { put("type", "string"); put("description", description) }
            if (required) this.required.add(name)
        }
        fun numberProp(name: String, description: String, required: Boolean = false) {
            properties[name] = buildJsonObject { put("type", "number"); put("description", description) }
            if (required) this.required.add(name)
        }
    }
}
