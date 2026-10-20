package com.secure.reader.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Library row for a document the user has opened.
 * The primary key is the SHA-256 of the file bytes, which doubles as the dedupe key.
 */
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val uriString: String,
    val fileName: String,
    val mimeType: String,
    val formatTag: String,
    val sizeBytes: Long,
    val lastAccessed: Long,
    val lastReadPosition: String? = null,
)
