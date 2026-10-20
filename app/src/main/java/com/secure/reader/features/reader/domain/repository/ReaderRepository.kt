package com.secure.reader.features.reader.domain.repository

import android.net.Uri
import com.secure.reader.features.reader.domain.model.OpenedDocument

/** The file's format has no engine behind it. */
class UnsupportedDocumentException(fileName: String) :
    Exception("No engine handles \"$fileName\" yet.")

/** The picker URI could not be persisted, streamed or decoded. */
class DocumentAccessException(cause: Throwable? = null) :
    Exception("The file could not be read.", cause)

interface ReaderRepository {
    /** Persists the SAF grant, dedupes by hash, caches a seekable copy, indexes it. */
    suspend fun open(uri: Uri): OpenedDocument

    /** Re-opens an indexed document from its cached copy. */
    suspend fun reopen(id: String): OpenedDocument
}
