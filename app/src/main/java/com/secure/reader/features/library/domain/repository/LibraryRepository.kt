package com.secure.reader.features.library.domain.repository

import com.secure.reader.features.library.domain.model.RecentDocument
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun observeRecent(): Flow<List<RecentDocument>>
    suspend fun delete(id: String)
}
