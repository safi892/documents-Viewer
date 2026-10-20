package com.secure.reader.features.library.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.secure.reader.core.database.DocumentDao
import com.secure.reader.core.file.DocumentCache
import com.secure.reader.features.library.data.mapper.toDomain
import com.secure.reader.features.library.domain.model.RecentDocument
import com.secure.reader.features.library.domain.repository.LibraryRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LibraryRepositoryImpl(
    private val context: Context,
    private val dao: DocumentDao,
) : LibraryRepository {

    override fun observeRecent(): Flow<List<RecentDocument>> =
        dao.observeRecent().map { rows -> rows.map { it.toDomain() } }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.getById(id)?.let { entity ->
            File(context.filesDir, DocumentCache.DIR)
                .listFiles()
                ?.filter { it.name.startsWith("$id.") }
                ?.forEach { it.delete() }
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(entity.uriString),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Provider never granted a persistable permission; nothing to release.
            }
        }
        dao.deleteById(id)
    }
}
