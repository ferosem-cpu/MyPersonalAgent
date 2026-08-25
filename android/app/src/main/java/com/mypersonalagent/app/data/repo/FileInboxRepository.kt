package com.mypersonalagent.app.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.mypersonalagent.app.data.local.FileDao
import com.mypersonalagent.app.data.local.FileEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class IngestResult(
    val file: FileEntity,
    val copiedToDriveFolder: Boolean,
    val uploadedToDriveApi: Boolean,
)

@Singleton
class FileInboxRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: FileDao,
    private val settings: SettingsRepository,
    private val driveBackup: DriveBackupRepository,
    private val shareBus: IncomingShareBus,
) {
    val files: Flow<List<FileEntity>> = dao.observeAll()

    suspend fun ingestUri(uri: Uri, source: String = "share"): IngestResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = queryDisplayName(uri) ?: "file-${System.currentTimeMillis()}"
        val mime = FileClassifier.mimeOf(name, resolver.getType(uri))
        val category = FileClassifier.category(name, mime)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not read $name")

        ingestBytes(name, mime, category, bytes, source)
    }

    suspend fun ingestBytes(
        displayName: String,
        mimeType: String,
        category: String,
        bytes: ByteArray,
        source: String,
    ): IngestResult = withContext(Dispatchers.IO) {
        val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val localDir = File(context.filesDir, "uploads/$category").apply { mkdirs() }
        val localFile = uniqueFile(localDir, safeName)
        localFile.writeBytes(bytes)

        var copiedToFolder = false
        val tree = settings.driveFolderUri.first()?.takeIf { it.isNotBlank() }
        if (tree != null) {
            copiedToFolder = runCatching {
                copyIntoTree(Uri.parse(tree), category, localFile.name, mimeType, bytes)
            }.getOrDefault(false)
        }

        var uploadedApi = false
        if (driveBackup.isSignedIn()) {
            uploadedApi = runCatching {
                driveBackup.uploadUserFile(localFile.name, bytes, mimeType, category)
                true
            }.getOrDefault(false)
        }

        val entity = FileEntity(
            id = UUID.randomUUID().toString(),
            displayName = localFile.name,
            category = category,
            mimeType = mimeType,
            localPath = localFile.absolutePath,
            sizeBytes = bytes.size.toLong(),
            source = source,
            created = OffsetDateTime.now().toString(),
        )
        dao.upsert(entity)

        val where = buildList {
            add("saved on phone in $category")
            if (copiedToFolder) add("copied to your Drive folder")
            if (uploadedApi) add("uploaded with Google sign-in")
        }.joinToString(", ")
        shareBus.emit("${entity.displayName} → $where")
        IngestResult(entity, copiedToFolder, uploadedApi)
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base = File(dir, name)
        if (!base.exists()) return base
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it == name) "" else ".$it" }
        var i = 2
        while (true) {
            val candidate = File(dir, "$stem-$i$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun copyIntoTree(treeUri: Uri, category: String, name: String, mime: String, bytes: ByteArray): Boolean {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        val folder = root.findFile(category)?.takeIf { it.isDirectory }
            ?: root.createDirectory(category)
            ?: return false
        val existing = folder.findFile(name)
        if (existing != null) existing.delete()
        val dest = folder.createFile(mime, name) ?: return false
        context.contentResolver.openOutputStream(dest.uri)?.use { it.write(bytes) } ?: return false
        return true
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
}
