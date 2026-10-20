package com.secure.reader.features.reader.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.secure.reader.core.database.DocumentDao
import com.secure.reader.core.database.DocumentEntity
import com.secure.reader.core.file.DocumentCache
import com.secure.reader.core.file.Format
import com.secure.reader.features.reader.domain.model.OpenedDocument
import com.secure.reader.features.reader.domain.repository.DocumentAccessException
import com.secure.reader.features.reader.domain.repository.ReaderRepository
import com.secure.reader.features.reader.domain.repository.UnsupportedDocumentException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ReaderRepositoryImpl(
    private val context: Context,
    private val dao: DocumentDao,
) : ReaderRepository {

    override suspend fun open(uri: Uri): OpenedDocument = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not every provider offers durable grants; a one-shot open still works.
        }

        val (fileName, sizeBytes) = queryDisplayInfo(uri)
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val format = Format.resolve(mimeType, fileName)
            ?: throw UnsupportedDocumentException(fileName)

        val (file, hash) = streamToCache(uri, fileName, format)
            ?: throw DocumentAccessException()

        val now = System.currentTimeMillis()
        dao.upsert(
            DocumentEntity(
                id = hash,
                uriString = uri.toString(),
                fileName = fileName,
                mimeType = mimeType,
                formatTag = format.tag,
                sizeBytes = if (sizeBytes > 0) sizeBytes else file.length(),
                lastAccessed = now,
            ),
        )

        OpenedDocument(
            id = hash,
            uriString = uri.toString(),
            fileName = fileName,
            mimeType = mimeType,
            format = format,
            sizeBytes = file.length(),
            lastAccessed = now,
            cachedFile = file,
        )
    }

    override suspend fun reopen(id: String): OpenedDocument = withContext(Dispatchers.IO) {
        val entity = dao.getById(id) ?: throw DocumentAccessException()
        val format = Format.entries.firstOrNull { it.tag == entity.formatTag }
            ?: throw UnsupportedDocumentException(entity.fileName)
        val file = DocumentCache.cachedFileFor(context.filesDir, id, entity.fileName, format)

        if (file.exists()) {
            val now = System.currentTimeMillis()
            dao.upsert(entity.copy(lastAccessed = now))
            return@withContext OpenedDocument(
                id = entity.id,
                uriString = entity.uriString,
                fileName = entity.fileName,
                mimeType = entity.mimeType,
                format = format,
                sizeBytes = entity.sizeBytes,
                lastAccessed = now,
                cachedFile = file,
            )
        }

        // The copy can be gone while the row survives — the app's data was
        // cleared, a cleaner swept the cache. The grant on the original URI is
        // usually still live, so re-read the source rather than turning the tap
        // into an error the user cannot act on.
        val recovered = open(Uri.parse(entity.uriString))
        // A different hash means the file changed underneath us, so the row we
        // came from now points at a copy that will never exist again.
        if (recovered.id != entity.id) dao.deleteById(entity.id)
        recovered
    }

    /**
     * Streams the content URI once, hashing as it writes, then renames to
     * `<sha>.<ext>`.
     *
     * Cooperative: if the user picks another file while a large copy is running,
     * `flatMapLatest` cancels this one, and the loop stops at the next megabyte
     * instead of letting a stale read finish and fight the new one for the disk.
     */
    private suspend fun streamToCache(uri: Uri, fileName: String, format: Format): Pair<File, String>? {
        val cacheDir = DocumentCache.directory(context.filesDir).apply { mkdirs() }
        val temp = File(cacheDir, "partial-${UUID.randomUUID()}")
        val digest = MessageDigest.getInstance("SHA-256")

        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return null
            input.use { source ->
                FileOutputStream(temp).use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var sinceCheck = 0
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        digest.update(buffer, 0, read)
                        sink.write(buffer, 0, read)
                        sinceCheck += read
                        if (sinceCheck >= CANCEL_CHECK_BYTES) {
                            sinceCheck = 0
                            currentCoroutineContext().ensureActive()
                        }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = DocumentCache.cachedFileFor(context.filesDir, hash, fileName, format)
            if (target.exists()) {
                temp.delete()
            } else {
                temp.renameTo(target)
            }
            target to hash
        } catch (cancelled: CancellationException) {
            temp.delete()
            throw cancelled
        } catch (error: IOException) {
            temp.delete()
            throw DocumentAccessException(error)
        }
    }

    private data class DisplayInfo(val name: String, val size: Long)

    private fun queryDisplayInfo(uri: Uri): DisplayInfo {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        var size = -1L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        cursor.getString(nameIndex)?.let { name = it }
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {
            // Keep the URI-derived name; size falls back to the cached copy length.
        }
        return DisplayInfo(name.ifBlank { "document" }, size)
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        /** How often the copy checks for cancellation: at most 1 MiB of stale IO. */
        private const val CANCEL_CHECK_BYTES = 1 * 1024 * 1024
    }
}
