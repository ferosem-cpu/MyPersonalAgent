package com.mypersonalagent.app.data.repo

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.mypersonalagent.app.data.local.ContactDao
import com.mypersonalagent.app.data.local.ContactEntity
import com.mypersonalagent.app.data.local.EntryDao
import com.mypersonalagent.app.data.local.EntryEntity
import com.mypersonalagent.app.data.local.NoteDao
import com.mypersonalagent.app.data.local.NoteEntity
import com.mypersonalagent.app.data.local.TodoDao
import com.mypersonalagent.app.data.local.TodoEntity
import com.mypersonalagent.app.di.DRIVE_FILE_SCOPE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
private fun JsonElement.jsonPrimitiveOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

@kotlinx.serialization.Serializable
private data class BackupSnapshot(
    val version: Int = 1,
    val todos: List<TodoEntity>,
    val entries: List<EntryEntity>,
    val notes: List<NoteEntity>,
    val contacts: List<ContactEntity>,
)

private const val ROOT_FOLDER_NAME = "MyPersonalAgent"
private const val BACKUP_FILE_NAME = "mypersonalagent_backup.json"
private const val DRIVE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
private const val FOLDER_MIME = "application/vnd.google-apps.folder"

@Singleton
class DriveBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("drive") private val httpClient: OkHttpClient,
    private val todoDao: TodoDao,
    private val entryDao: EntryDao,
    private val noteDao: NoteDao,
    private val contactDao: ContactDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun currentAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun isSignedIn(): Boolean = currentAccount() != null

    private suspend fun accessToken(account: Account): String = withContext(Dispatchers.IO) {
        GoogleAuthUtil.getToken(context, account, "oauth2:$DRIVE_FILE_SCOPE")
    }

    suspend fun backupNow(): String {
        val account = currentAccount()?.account ?: error("Not signed in to Google Drive.")
        val token = accessToken(account)
        val snapshot = BackupSnapshot(
            todos = todoDao.observeAll().first(),
            entries = entryDao.observeAll().first(),
            notes = noteDao.search(""),
            contacts = contactDao.observeAll().first(),
        )
        val payload = json.encodeToString(snapshot)
        val rootId = getOrCreateFolder(token, ROOT_FOLDER_NAME, parentId = null)
        val existingId = findChildFileId(token, rootId, BACKUP_FILE_NAME)
        return if (existingId != null) {
            updateFile(token, existingId, payload)
            existingId
        } else {
            createFile(token, payload, BACKUP_FILE_NAME, "application/json", rootId)
        }
    }

    suspend fun restoreLatest(): Int {
        val account = currentAccount()?.account ?: error("Not signed in to Google Drive.")
        val token = accessToken(account)
        val rootId = getOrCreateFolder(token, ROOT_FOLDER_NAME, parentId = null)
        val fileId = findChildFileId(token, rootId, BACKUP_FILE_NAME) ?: error("No backup found in Drive/MyPersonalAgent.")

        val body = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$DRIVE_FILES_URL/$fileId?alt=media")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("Drive download failed: ${resp.code}")
                resp.body?.string() ?: error("Empty backup file")
            }
        }
        val snapshot = json.decodeFromString(BackupSnapshot.serializer(), body)
        snapshot.todos.forEach { todoDao.upsert(it) }
        snapshot.entries.forEach { entryDao.upsert(it) }
        snapshot.notes.forEach { noteDao.upsert(it) }
        snapshot.contacts.forEach { contactDao.upsert(it) }
        return snapshot.todos.size + snapshot.entries.size + snapshot.notes.size + snapshot.contacts.size
    }

    suspend fun uploadUserFile(name: String, bytes: ByteArray, mimeType: String, category: String): String {
        val account = currentAccount()?.account ?: error("Not signed in to Google Drive.")
        val token = accessToken(account)
        val rootId = getOrCreateFolder(token, ROOT_FOLDER_NAME, parentId = null)
        val folderId = getOrCreateFolder(token, category, parentId = rootId)
        val existing = findChildFileId(token, folderId, name)
        return if (existing != null) {
            updateBytes(token, existing, bytes, mimeType)
            existing
        } else {
            createBytes(token, bytes, name, mimeType, folderId)
        }
    }

    private suspend fun getOrCreateFolder(token: String, name: String, parentId: String?): String = withContext(Dispatchers.IO) {
        val parentClause = if (parentId != null) "and '$parentId' in parents" else "and 'root' in parents"
        val q = "name = '$name' and mimeType = '$FOLDER_MIME' and trashed = false $parentClause"
        val encoded = URLEncoder.encode(q, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("$DRIVE_FILES_URL?q=$encoded&spaces=drive&fields=files(id,name)")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        val existing = httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: return@use null
            if (!resp.isSuccessful) return@use null
            json.parseToJsonElement(body).jsonObjectOrNull()
                ?.get("files")?.jsonArrayOrNull()
                ?.firstOrNull()?.jsonObjectOrNull()
                ?.get("id")?.jsonPrimitiveOrNull()
        }
        if (existing != null) return@withContext existing

        val metadata = buildString {
            append("{\"name\":")
            append(JsonPrimitive(name))
            append(",\"mimeType\":\"$FOLDER_MIME\"")
            if (parentId != null) append(",\"parents\":[\"$parentId\"]")
            append("}")
        }
        val create = Request.Builder()
            .url("$DRIVE_FILES_URL?fields=id")
            .addHeader("Authorization", "Bearer $token")
            .post(metadata.toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(create).execute().use { resp ->
            val body = resp.body?.string() ?: error("Empty folder create")
            if (!resp.isSuccessful) error("Drive folder create failed: ${resp.code} $body")
            json.parseToJsonElement(body).jsonObjectOrNull()?.get("id")?.jsonPrimitiveOrNull()
                ?: error("Folder create missing id")
        }
    }

    private suspend fun findChildFileId(token: String, parentId: String, name: String): String? = withContext(Dispatchers.IO) {
        val q = "name = '$name' and '$parentId' in parents and trashed = false"
        val encoded = URLEncoder.encode(q, Charsets.UTF_8.name())
        val request = Request.Builder()
            .url("$DRIVE_FILES_URL?q=$encoded&spaces=drive&fields=files(id)")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string() ?: return@use null
            json.parseToJsonElement(body).jsonObjectOrNull()
                ?.get("files")?.jsonArrayOrNull()
                ?.firstOrNull()?.jsonObjectOrNull()
                ?.get("id")?.jsonPrimitiveOrNull()
        }
    }

    private suspend fun createFile(token: String, payload: String, name: String, mime: String, parentId: String): String {
        return createBytes(token, payload.toByteArray(Charsets.UTF_8), name, mime, parentId)
    }

    private suspend fun createBytes(token: String, bytes: ByteArray, name: String, mime: String, parentId: String): String = withContext(Dispatchers.IO) {
        val metadata = "{\"name\":${JsonPrimitive(name)},\"parents\":[\"$parentId\"]}"
        val multipart = MultipartBody.Builder().setType(MultipartBody.MIXED)
            .addPart(metadata.toRequestBody("application/json".toMediaType()))
            .addPart(bytes.toRequestBody(mime.toMediaType()))
            .build()
        val request = Request.Builder()
            .url(DRIVE_UPLOAD_URL)
            .addHeader("Authorization", "Bearer $token")
            .post(multipart)
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: error("Empty upload response")
            if (!resp.isSuccessful) error("Drive upload failed: ${resp.code} $body")
            json.parseToJsonElement(body).jsonObjectOrNull()?.get("id")?.jsonPrimitiveOrNull()
                ?: error("Upload response missing file id")
        }
    }

    private suspend fun updateFile(token: String, fileId: String, payload: String) {
        updateBytes(token, fileId, payload.toByteArray(Charsets.UTF_8), "application/json")
    }

    private suspend fun updateBytes(token: String, fileId: String, bytes: ByteArray, mime: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
            .addHeader("Authorization", "Bearer $token")
            .patch(bytes.toRequestBody(mime.toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Drive update failed: ${resp.code} ${resp.body?.string()}")
        }
    }
}
