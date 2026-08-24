package com.mypersonalagent.app.data.repo

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

private const val SYSTEM_PROMPT = """
You are the on-device assistant for MyPersonalAgent, a personal productivity app. You can
create and manage the user's todos, log work entries, and save/recall notes using the tools
provided. Everything you do stays on this phone - there is no server. Be concise. When you
take an action (add a todo, log work, save a note), confirm briefly what you did. When you
need an id for complete_todo, call list_todos first if you don't already have it from context.
"""

data class ProviderConfig(
    val name: String,
    val apiKey: String,
    val model: String,
    val endpointUrl: String,
    val type: String, // "openai" or "anthropic"
)

data class ChatReply(
    val text: String,
    val provider: String? = null,
)

@Singleton
class ChatRepository @Inject constructor(
    @Named("anthropic") private val httpClient: OkHttpClient,
    private val settings: SettingsRepository,
    private val todoRepository: TodoRepository,
    private val entryRepository: EntryRepository,
    private val memoryRepository: MemoryRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** In-memory only - conversation resets on process death. */
    private val history = mutableListOf<JsonObject>()

    suspend fun send(message: String): ChatReply {
        val configuredProviders = getConfiguredProviders()
        if (configuredProviders.isEmpty()) {
            return ChatReply(
                "No API keys saved yet. Open Settings (gear icon), paste at least one key, tap Save all keys, then try again.",
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
        return ChatReply("All configured providers failed. Last error: $detail")
    }

    private suspend fun getConfiguredProviders(): List<ProviderConfig> {
        val selected = settings.llmProvider.first()?.trim()?.lowercase() ?: "auto"

        val nvidiaKey = settings.nvidiaApiKey.first()?.trim()
        val nvidiaModel = settings.nvidiaModel.first()?.trim()?.ifBlank { null } ?: "nvidia/llama-3.3-nemotron-super-49b-v1.5"

        val anthropicKey = settings.anthropicApiKey.first()?.trim()
        val anthropicModel = settings.anthropicModel.first()?.trim()?.ifBlank { null } ?: "claude-sonnet-5"

        val openaiKey = settings.openaiApiKey.first()?.trim()
        val openaiModel = settings.openaiModel.first()?.trim()?.ifBlank { null } ?: "gpt-4o"

        val googleKey = settings.googleApiKey.first()?.trim()
        val googleModel = settings.googleModel.first()?.trim()?.ifBlank { null } ?: "gemini-2.0-flash"

        val openrouterKey = settings.openrouterApiKey.first()?.trim()
        val openrouterModel = settings.openrouterModel.first()?.trim()?.ifBlank { null } ?: "anthropic/claude-sonnet-5"

        val grokKey = settings.grokApiKey.first()?.trim()
        val grokModel = settings.grokModel.first()?.trim()?.ifBlank { null } ?: "grok-3"

        val list = mutableListOf<ProviderConfig>()

        if (!nvidiaKey.isNullOrBlank()) {
            list.add(ProviderConfig("nvidia", nvidiaKey, nvidiaModel, "https://integrate.api.nvidia.com/v1/chat/completions", "openai"))
        }
        if (!anthropicKey.isNullOrBlank()) {
            list.add(ProviderConfig("anthropic", anthropicKey, anthropicModel, "https://api.anthropic.com/v1/messages", "anthropic"))
        }
        if (!openaiKey.isNullOrBlank()) {
            list.add(ProviderConfig("openai", openaiKey, openaiModel, "https://api.openai.com/v1/chat/completions", "openai"))
        }
        if (!googleKey.isNullOrBlank()) {
            list.add(ProviderConfig("google", googleKey, googleModel, "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "openai"))
        }
        if (!openrouterKey.isNullOrBlank()) {
            list.add(ProviderConfig("openrouter", openrouterKey, openrouterModel, "https://openrouter.ai/api/v1/chat/completions", "openai"))
        }
        if (!grokKey.isNullOrBlank()) {
            list.add(ProviderConfig("grok", grokKey, grokModel, "https://api.x.ai/v1/chat/completions", "openai"))
        }

        if (selected != "auto") {
            val preferred = list.filter { it.name == selected }
            val rest = list.filter { it.name != selected }
            return preferred + rest
        }

        return list
    }

    private suspend fun executeTurn(provider: ProviderConfig, userMessage: String): String {
        return if (provider.type == "anthropic") {
            executeTurnAnthropic(provider, userMessage)
        } else {
            executeTurnOpenAI(provider, userMessage)
        }
    }

    private suspend fun executeTurnAnthropic(provider: ProviderConfig, userMessage: String): String {
        history.add(buildJsonObject { put("role", "user"); put("content", userMessage) })

        repeat(MAX_TOOL_ROUNDS) {
            val response = callAnthropic(provider.apiKey, provider.model)
            val contentBlocks = response["content"]?.jsonArray ?: error("Invalid Anthropic response: missing content")
            val stopReason = response["stop_reason"]?.jsonPrimitive?.contentOrNull

            history.add(buildJsonObject { put("role", "assistant"); put("content", contentBlocks) })

            if (stopReason != "tool_use") {
                val text = contentBlocks
                    .mapNotNull { it as? JsonObject }
                    .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    ?.get("text")?.jsonPrimitive?.contentOrNull
                    ?.trim()
                if (text.isNullOrBlank()) error("${provider.name} returned no text")
                return text
            }

            val toolResults = buildJsonArray {
                contentBlocks.mapNotNull { it as? JsonObject }
                    .filter { it["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }
                    .forEach { block ->
                        val id = block["id"]!!.jsonPrimitive.content
                        val name = block["name"]!!.jsonPrimitive.content
                        val input = block["input"]?.jsonObject ?: JsonObject(emptyMap())
                        val resultText = runCatching { executeTool(name, input) }
                            .getOrElse { "Error running $name: ${it.message}" }
                        add(
                            buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", id)
                                put("content", resultText)
                            },
                        )
                    }
            }
            history.add(buildJsonObject { put("role", "user"); put("content", toolResults) })
        }
        error("Reached maximum tool execution rounds.")
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

    fun clearHistory() {
        history.clear()
    }

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
            if (todos.isEmpty()) {
                "No todos."
            } else {
                todos.joinToString("\n") { t ->
                    "- [id=${t.id}] ${t.title}" + (t.due?.let { " (due $it)" } ?: "")
                }
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
        else -> "Unknown tool: $name"
    }

    private suspend fun callAnthropic(apiKey: String, model: String): JsonObject {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 1024)
            put("system", SYSTEM_PROMPT.trim())
            put("messages", JsonArray(history.toList()))
            put("tools", ANTHROPIC_TOOLS)
        }
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: error("Empty response from Anthropic API")
                if (!resp.isSuccessful) error("Anthropic API error ${resp.code}: $bodyStr")
                json.parseToJsonElement(bodyStr).jsonObject
            }
        }
    }

    private suspend fun callOpenAI(provider: ProviderConfig, messages: List<JsonObject>): JsonObject {
        val body = buildJsonObject {
            put("model", provider.model)
            put("messages", JsonArray(messages))
            put("tools", OPENAI_TOOLS)
        }
        val requestBuilder = Request.Builder()
            .url(provider.endpointUrl)
            .addHeader("Authorization", "Bearer ${provider.apiKey}")
            .addHeader("content-type", "application/json")

        if (provider.name == "openrouter") {
            requestBuilder.addHeader("HTTP-Referer", "https://github.com/ferosem-cpu/MyPersonalAgent")
            requestBuilder.addHeader("X-Title", "MyPersonalAgent")
        }

        val request = requestBuilder
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
        private val ANTHROPIC_TOOLS = buildJsonArray {
            add(anthropicTool("add_todo", "Create a new todo.") {
                stringProp("title", "The todo's title.", required = true)
                stringProp("project", "Optional project/category name.")
                stringProp("due", "Optional ISO-8601 due date/time.")
            })
            add(anthropicTool("complete_todo", "Mark a todo complete by its id. Call list_todos first if you don't have the id.") {
                stringProp("id", "The todo's id.", required = true)
            })
            add(anthropicTool("list_todos", "List todos, optionally filtered by status.") {
                stringProp("status", "One of: open, done, snoozed, all. Defaults to open.")
            })
            add(anthropicTool("log_work", "Log a completed work entry.") {
                stringProp("title", "What was done.", required = true)
                stringProp("desc", "Optional longer description.")
                stringProp("project", "Optional project/category name.")
                numberProp("minutes", "Minutes spent.")
            })
            add(anthropicTool("remember", "Save a note to memory.") {
                stringProp("text", "The note text.", required = true)
            })
            add(anthropicTool("recall", "Search saved notes.") {
                stringProp("query", "Search text.")
            })
        }

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
        }

        private fun anthropicTool(name: String, description: String, buildProps: PropsBuilder.() -> Unit): JsonObject {
            val builder = PropsBuilder().apply(buildProps)
            return buildJsonObject {
                put("name", name)
                put("description", description)
                put(
                    "input_schema",
                    buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject { builder.properties.forEach { (k, v) -> put(k, v) } })
                        put("required", buildJsonArray { builder.required.forEach { add(JsonPrimitive(it)) } })
                    },
                )
            }
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
