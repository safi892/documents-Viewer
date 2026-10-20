package com.secure.reader.features.reader.domain.model

import com.secure.reader.core.file.Format
import java.io.File

data class OpenedDocument(
    val id: String,
    val uriString: String,
    val fileName: String,
    val mimeType: String,
    val format: Format,
    val sizeBytes: Long,
    val lastAccessed: Long,
    /** Seekable on-device copy the render engines read from. */
    val cachedFile: File,
)
