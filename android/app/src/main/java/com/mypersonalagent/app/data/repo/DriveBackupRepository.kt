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
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray
private fun JsonElement.jsonPrimitiveOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

/** Snapshot format written to Drive. Kept intentionally simple/flat - this is a backup format,
 * not an API contract, so it can just mirror the Room entities directly. */
@kotlinx.serialization.Serializable
private data class BackupSnapshot(
    val version: Int = 1,
    val todos: List<TodoEntity>,
    val entries: List<EntryEntity>,
    val notes: List<NoteEntity>,
    val contacts: List<ContactEntity>,
)

private const val BACKUP_FILE_NAME = "mypersonalagent_backup.json"
private const val DRIVE_UPLOAD_URL =
    "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id&spaces=appDataFolder"
private const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"

/**
 * Standalone (2026-08-12 pivot, Phase E): periodic + on-demand backup of all local data to the
 * user's own Google Drive (appDataFolder - invisible in their normal Drive UI, scoped to this
 * app only via drive.file). Restorable on a fresh install. Best-effort: failures are surfaced
 * as exceptions to the caller (Settings screen / BackupWorker) rather than swallowed, since
 * unlike Telegram this is something the user explicitly asked to rely on for data safety.
 */
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

    /** Blocking network call - always invoke from a background dispatcher. */
    private suspend fun accessToken(account: Account): String = withContext(Dispatchers.IO) {
        GoogleAuthUtil.getToken(context, account, "oauth2:$DRIVE_FILE_SCOPE")
    }

    /** Uploads a fresh snapshot, overwriting the previous backup if one exists. Returns the
     * Drive file id. Throws on any failure (no signed-in account, network error, API error). */
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

        val existingId = findExistingBackupFileId(token)
        return if (existingId != null) {
            updateFile(token, existingId, payload)
            existingId
        } else {
            createFile(token, payload)
        }
    }

    /** Downloads and restores the latest backup into Room. Existing local rows with the same
     * id are overwritten (last-write-wins is irrelevant here - this is an explicit user action,
     * not an automatic merge). Returns the number of records restored. Throws on failure. */
    suspend fun restoreLatest(): Int {
        val account = currentAccount()?.account ?: error("Not signed in to Google Drive.")
        val token = accessToken(account)
        val fileId = findExistingBackupFileId(token) ?: error("No backup found on Drive.")

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

    private suspend fun findExistingBackupFileId(token: String): String? = withContext(Dispatchers.IO) {
        val url = "$DRIVE_FILES_URL?spaces=appDataFolder&q=name='$BACKUP_FILE_NAME'&fields=files(id)"
        val request = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").get().build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Drive list failed: ${resp.code}")
            val body = resp.body?.string() ?: return@use null
            val files = json.parseToJsonElement(body).let {
                it.jsonObjectOrNull()?.get("files")?.jsonArrayOrNull()
            } ?: return@use null
            files.firstOrNull()?.jsonObjectOrNull()?.get("id")?.jsonPrimitiveOrNull()
        }
    }

    private suspend fun createFile(token: String, payload: String) = withContext(Dispatchers.IO) {
        val metadata = """{"name":"$BACKUP_FILE_NAME","parents":["appDataFolder"]}"""
        val multipart = MultipartBody.Builder().setType(MultipartBody.MIXED)
            .addPart(metadata.toRequestBody("application/json".toMediaType()))
            .addPart(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val request = Request.Builder().url(DRIVE_UPLOAD_URL)
            .addHeader("Authorization", "Bearer $token")
            .post(multipart)
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Drive upload failed: ${resp.code} ${resp.body?.string()}")
            val body = resp.body?.string() ?: error("Empty upload response")
            json.parseToJsonElement(body).jsonObjectOrNull()?.get("id")?.jsonPrimitiveOrNull()
                ?: error("Upload response missing file id")
        }
    }

    private suspend fun updateFile(token: String, fileId: String, payload: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
            .addHeader("Authorization", "Bearer $token")
            .patch(payload.toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Drive update failed: ${resp.code} ${resp.body?.string()}")
        }
    }
}
