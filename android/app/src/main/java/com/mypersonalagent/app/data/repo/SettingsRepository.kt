package com.mypersonalagent.app.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    }

    val serverUrl: Flow<String?> = context.dataStore.data.map { it[Keys.SERVER_URL] }
    val apiToken: Flow<String?> = context.dataStore.data.map { it[Keys.API_TOKEN] }
    val lastSync: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_SYNC] }
    val avatarUri: Flow<String?> = context.dataStore.data.map { it[Keys.AVATAR_URI] }

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
}
