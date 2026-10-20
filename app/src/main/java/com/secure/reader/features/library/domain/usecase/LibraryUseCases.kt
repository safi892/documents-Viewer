package com.secure.reader.features.library.domain.usecase

import com.secure.reader.features.library.domain.model.RecentDocument
import com.secure.reader.features.library.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow

class ObserveRecentDocuments(private val repository: LibraryRepository) {
    operator fun invoke(): Flow<List<RecentDocument>> = repository.observeRecent()
}

class DeleteDocument(private val repository: LibraryRepository) {
    suspend operator fun invoke(id: String) = repository.delete(id)
}
