package com.secure.reader.features.reader.domain.usecase

import android.net.Uri
import com.secure.reader.features.reader.domain.model.OpenedDocument
import com.secure.reader.features.reader.domain.repository.ReaderRepository

class OpenDocumentUseCase(private val repository: ReaderRepository) {
    suspend operator fun invoke(uri: Uri): OpenedDocument = repository.open(uri)
}

class ReopenDocumentUseCase(private val repository: ReaderRepository) {
    suspend operator fun invoke(id: String): OpenedDocument = repository.reopen(id)
}
