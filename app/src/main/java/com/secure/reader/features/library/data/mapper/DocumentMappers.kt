package com.secure.reader.features.library.data.mapper

import com.secure.reader.core.database.DocumentEntity
import com.secure.reader.core.file.Format
import com.secure.reader.features.library.domain.model.RecentDocument

fun DocumentEntity.toDomain(): RecentDocument = RecentDocument(
    id = id,
    uriString = uriString,
    fileName = fileName,
    mimeType = mimeType,
    format = Format.entries.firstOrNull { it.tag == formatTag }
        ?: Format.resolve(mimeType, fileName)
        ?: Format.PDF,
    sizeBytes = sizeBytes,
    lastAccessed = lastAccessed,
)
