package com.secure.reader.features.library.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.reader.features.library.domain.model.RecentDocument
import com.secure.reader.features.library.domain.usecase.DeleteDocument
import com.secure.reader.features.library.domain.usecase.ObserveRecentDocuments
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data object Empty : LibraryUiState
    data class Ready(val documents: List<RecentDocument>) : LibraryUiState
    data class Failed(val message: String) : LibraryUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val observeRecentDocuments: ObserveRecentDocuments,
    private val deleteDocument: DeleteDocument,
) : ViewModel() {

    private val _state = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val retrySignal = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            retrySignal
                .flatMapLatest { observeRecentDocuments() }
                .catch { error ->
                    _state.value = LibraryUiState.Failed(
                        error.message ?: "The library could not be read.",
                    )
                }
                .collect { documents ->
                    _state.value = if (documents.isEmpty()) {
                        LibraryUiState.Empty
                    } else {
                        LibraryUiState.Ready(documents)
                    }
                }
        }
    }

    fun retry() {
        _state.value = LibraryUiState.Loading
        retrySignal.value++
    }

    fun delete(id: String) {
        viewModelScope.launch { deleteDocument(id) }
    }
}
