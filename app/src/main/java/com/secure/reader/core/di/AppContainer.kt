package com.secure.reader.core.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import com.secure.reader.core.database.AppDatabase
import com.secure.reader.features.library.data.repository.LibraryRepositoryImpl
import com.secure.reader.features.library.domain.repository.LibraryRepository
import com.secure.reader.features.library.domain.usecase.DeleteDocument
import com.secure.reader.features.library.domain.usecase.ObserveRecentDocuments
import com.secure.reader.features.library.ui.LibraryViewModel
import com.secure.reader.features.reader.data.repository.ReaderRepositoryImpl
import com.secure.reader.features.reader.domain.repository.ReaderRepository
import com.secure.reader.features.reader.domain.usecase.OpenDocumentUseCase
import com.secure.reader.features.reader.domain.usecase.ReopenDocumentUseCase
import com.secure.reader.features.reader.ui.ReaderViewModel

/**
 * Small manual DI graph — no framework, one container owned by the Application.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "reader.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val libraryRepository: LibraryRepository by lazy {
        LibraryRepositoryImpl(appContext, database.documentDao())
    }

    val readerRepository: ReaderRepository by lazy {
        ReaderRepositoryImpl(appContext, database.documentDao())
    }

    val libraryViewModelFactory: ViewModelProvider.Factory by lazy {
        viewModelFactory {
            initializer {
                LibraryViewModel(
                    observeRecentDocuments = ObserveRecentDocuments(libraryRepository),
                    deleteDocument = DeleteDocument(libraryRepository),
                )
            }
        }
    }

    val readerViewModelFactory: ViewModelProvider.Factory by lazy {
        viewModelFactory {
            initializer {
                ReaderViewModel(
                    openDocument = OpenDocumentUseCase(readerRepository),
                    reopenDocument = ReopenDocumentUseCase(readerRepository),
                )
            }
        }
    }
}
