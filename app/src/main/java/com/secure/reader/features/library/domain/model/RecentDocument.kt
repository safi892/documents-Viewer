package com.secure.reader.features.library.domain.model

import com.secure.reader.core.file.Format

data class RecentDocument(
    val id: String,
    val uriString: String,
    val fileName: String,
    val mimeType: String,
    val format: Format,
    val sizeBytes: Long,
    val lastAccessed: Long,
)
