package com.mypersonalagent.app.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    private val context: Context,
) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_TOKEN = stringPreferencesKey("api_token")
        val LAST_SYNC = stringPreferencesKey("last_sync")
        val AVATAR_URI = stringPreferencesKey("avatar_uri")
        val APP_ALIASES = stringPreferencesKey("app_aliases_json")
        val TELEGRAM_BOT_TOKEN = stringPreferencesKey("telegram_bot_token")
        val TELEGRAM_CHAT_ID = stringPreferencesKey("telegram_chat_id")
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val ANTHROPIC_API_KEY = stringPreferencesKey("anthropic_api_key")
        val ANTHROPIC_MODEL = stringPreferencesKey("anthropic_model")
        val NVIDIA_API_KEY = stringPreferencesKey("nvidia_api_key")
        val NVIDIA_MODEL = stringPreferencesKey("nvidia_model")
        val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        val GOOGLE_API_KEY = stringPreferencesKey("google_api_key")
        val GOOGLE_MODEL = stringPreferencesKey("google_model")
        val OPENROUTER_API_KEY = stringPreferencesKey("openrouter_api_key")
        val OPENROUTER_MODEL = stringPreferencesKey("openrouter_model")
        val GROK_API_KEY = stringPreferencesKey("grok_api_key")
        val GROK_MODEL = stringPreferencesKey("grok_model")
        val CUSTOM_API_KEY = stringPreferencesKey("custom_api_key")
        val CUSTOM_BASE_URL = stringPreferencesKey("custom_base_url")
        val CUSTOM_MODEL = stringPreferencesKey("custom_model")
        val DRIVE_FOLDER_URI = stringPreferencesKey("drive_folder_uri")
    }

    val serverUrl: Flow<String?> = context.dataStore.data.map { it[Keys.SERVER_URL] }
    val apiToken: Flow<String?> = context.dataStore.data.map { it[Keys.API_TOKEN] }
    val lastSync: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_SYNC] }
    val avatarUri: Flow<String?> = context.dataStore.data.map { it[Keys.AVATAR_URI] }
    val telegramBotToken: Flow<String?> = context.dataStore.data.map { it[Keys.TELEGRAM_BOT_TOKEN] }
    val telegramChatId: Flow<String?> = context.dataStore.data.map { it[Keys.TELEGRAM_CHAT_ID] }
    val llmProvider: Flow<String?> = context.dataStore.data.map { it[Keys.LLM_PROVIDER] }
    val anthropicApiKey: Flow<String?> = context.dataStore.data.map { it[Keys.ANTHROPIC_API_KEY] }
    val anthropicModel: Flow<String?> = context.dataStore.data.map { it[Keys.ANTHROPIC_MODEL] }
    val nvidiaApiKey: Flow<String?> = context.dataStore.data.map { it[Keys.NVIDIA_API_KEY] }
    val nvidiaModel: Flow<String?> = context.dataStore.data.map { it[Keys.NVIDIA_MODEL] }
    val openaiApiKey: Flow<String?> = context.dataStore.data.map { it[Keys.OPENAI_API_KEY] }
    val openaiModel: Flow<String?> = context.dataStore.data.map { it[Keys.OPENAI_MODEL] }
    val googleApiKey: Flow<String?> = context.dataStore.data.map { it[Keys.GOOGLE_API_KEY] }
    val googleModel: Flow<String?> = context.dataStore.data.map { it[Keys.GOOGLE_MODEL] }
    val openrouterApiKey: Flow<String?> = context.dataStore.data.map { it[Keys.OPENROUTER_API_KEY] }
    val openrouterModel: Flow<String?> = context.dataStore.data.map { it[Keys.OPENROUTER_MODEL] }
    val grokApiKey: Flow<String?> = context.dataStore.data.map { it[Keys.GROK_API_KEY] }
    val grokModel: Flow<String?> = context.dataStore.data.map { it[Keys.GROK_MODEL] }
    val customApiKey: Flow<String?> = context.dataStore.data.map { it[Keys.CUSTOM_API_KEY] }
    val customBaseUrl: Flow<String?> = context.dataStore.data.map { it[Keys.CUSTOM_BASE_URL] }
    val customModel: Flow<String?> = context.dataStore.data.map { it[Keys.CUSTOM_MODEL] }
    val driveFolderUri: Flow<String?> = context.dataStore.data.map { it[Keys.DRIVE_FOLDER_URI] }

    /** alias (lowercase, user-facing name) -> Android package name. Purely local, no server round-trip. */
    val appAliases: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.APP_ALIASES] ?: return@map emptyMap()
        runCatching {
            Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    suspend fun setAppAliases(aliases: Map<String, String>) {
        val json = buildJsonObject { aliases.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString()
        context.dataStore.edit { it[Keys.APP_ALIASES] = json }
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[Keys.SERVER_URL] = url }
    }

    suspend fun setApiToken(token: String) {
        context.dataStore.edit { it[Keys.API_TOKEN] = token }
    }

    suspend fun setLastSync(iso: String) {
        context.dataStore.edit { it[Keys.LAST_SYNC] = iso }
    }

    suspend fun setAvatarUri(uri: String) {
        context.dataStore.edit { it[Keys.AVATAR_URI] = uri }
    }

    suspend fun setTelegramBotToken(token: String) {
        context.dataStore.edit { it[Keys.TELEGRAM_BOT_TOKEN] = token }
    }

    suspend fun setTelegramChatId(chatId: String) {
        context.dataStore.edit { it[Keys.TELEGRAM_CHAT_ID] = chatId }
    }

    suspend fun setLlmProvider(provider: String) {
        context.dataStore.edit { it[Keys.LLM_PROVIDER] = provider }
    }

    suspend fun setAnthropicApiKey(key: String) {
        context.dataStore.edit { it[Keys.ANTHROPIC_API_KEY] = key }
    }

    suspend fun setAnthropicModel(model: String) {
        context.dataStore.edit { it[Keys.ANTHROPIC_MODEL] = model }
    }

    suspend fun setNvidiaApiKey(key: String) {
        context.dataStore.edit { it[Keys.NVIDIA_API_KEY] = key }
    }

    suspend fun setNvidiaModel(model: String) {
        context.dataStore.edit { it[Keys.NVIDIA_MODEL] = model }
    }

    suspend fun setOpenaiApiKey(key: String) {
        context.dataStore.edit { it[Keys.OPENAI_API_KEY] = key }
    }

    suspend fun setOpenaiModel(model: String) {
        context.dataStore.edit { it[Keys.OPENAI_MODEL] = model }
    }

    suspend fun setGoogleApiKey(key: String) {
        context.dataStore.edit { it[Keys.GOOGLE_API_KEY] = key }
    }

    suspend fun setGoogleModel(model: String) {
        context.dataStore.edit { it[Keys.GOOGLE_MODEL] = model }
    }

    suspend fun setOpenrouterApiKey(key: String) {
        context.dataStore.edit { it[Keys.OPENROUTER_API_KEY] = key }
    }

    suspend fun setOpenrouterModel(model: String) {
        context.dataStore.edit { it[Keys.OPENROUTER_MODEL] = model }
    }

    suspend fun setGrokApiKey(key: String) {
        context.dataStore.edit { it[Keys.GROK_API_KEY] = key }
    }

    suspend fun setGrokModel(model: String) {
        context.dataStore.edit { it[Keys.GROK_MODEL] = model }
    }

    suspend fun setCustomApiKey(key: String) {
        context.dataStore.edit { it[Keys.CUSTOM_API_KEY] = key }
    }

    suspend fun setCustomBaseUrl(url: String) {
        context.dataStore.edit { it[Keys.CUSTOM_BASE_URL] = url }
    }

    suspend fun setCustomModel(model: String) {
        context.dataStore.edit { it[Keys.CUSTOM_MODEL] = model }
    }

    suspend fun setDriveFolderUri(uri: String) {
        context.dataStore.edit { it[Keys.DRIVE_FOLDER_URI] = uri }
    }
}
