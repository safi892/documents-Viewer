package com.secure.reader.features.reader.ui

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secure.reader.features.reader.domain.model.OpenedDocument
import com.secure.reader.features.reader.domain.repository.DocumentAccessException
import com.secure.reader.features.reader.domain.repository.UnsupportedDocumentException
import com.secure.reader.features.reader.domain.usecase.OpenDocumentUseCase
import com.secure.reader.features.reader.domain.usecase.ReopenDocumentUseCase
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

data class ReaderUiState(
    val phase: Phase = Phase.LOADING,
    val document: OpenedDocument? = null,
    val errorMessage: String? = null,
) {
    enum class Phase { LOADING, READY, FAILED }
}

/**
 * One document open at a time, by construction.
 *
 * Every open request becomes a value on [requests]; a `flatMapLatest` pipeline
 * cancels whatever was still streaming the moment a newer request arrives, so
 * two intents fired back to back can never race each other into the state
 * (the previous `launch`-per-open version could show the *earlier* file's
 * result if it finished last).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    private val openDocument: OpenDocumentUseCase,
    private val reopenDocument: ReopenDocumentUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private sealed interface Request {
        data class FromUri(val uri: Uri) : Request
        data class FromId(val id: String) : Request
    }

    /**
     * `token` increments on retry: StateFlow de-duplicates equal values, so a
     * retry of the *same* request must still be a new value to re-trigger the
     * pipeline.
     */
    private data class LoadRequest(val request: Request, val token: Long = 0L)

    private val requests = MutableStateFlow<LoadRequest?>(null)

    init {
        viewModelScope.launch {
            requests
                .filterNotNull()
                .flatMapLatest { loadRequest ->
                    // The heavy open work runs on its own dispatcher (see runRequest
                    // and the repository layer). This coroutine only owns the UI state
                    // pipeline; it never blocks the main thread or holds a large buffer.
                    flow {
                        emit(ReaderUiState(ReaderUiState.Phase.LOADING))
                        emit(runRequest(loadRequest.request))
                    }
                }
                .catch { error ->
                    // runRequest already maps failures; anything escaping is a bug.
                    Log.e(TAG, "open pipeline failed", error)
                    emit(ReaderUiState(ReaderUiState.Phase.FAILED, errorMessage = FALLBACK))
                }
                .collect { _state.value = it }
        }
    }

    fun open(uri: Uri) {
        // Every open is a new value, so a second tap within the same frame never
        // lands before the first one has entered the pipeline.
        requests.value = LoadRequest(Request.FromUri(uri))
    }

    fun reopen(id: String) {
        requests.value = LoadRequest(Request.FromId(id))
    }

    fun retry() {
        // Retry is idempotent and safe to call from any thread; the pipeline
        // ignores a token that equals the current one.
        requests.value?.let { current ->
            requests.value = current.copy(token = current.token + 1)
        }
    }

    fun close() {
        requests.value = null
        _state.value = ReaderUiState()
    }

    private suspend fun runRequest(request: Request): ReaderUiState = try {
        val document = when (request) {
            is Request.FromUri -> openDocument(request.uri)
            is Request.FromId -> reopenDocument(request.id)
        }
        Log.d(TAG, "opened ${document.fileName} (${document.format.tag}) from ${document.uriString}")
        ReaderUiState(ReaderUiState.Phase.READY, document = document)
    } catch (error: UnsupportedDocumentException) {
        Log.w(TAG, "open rejected: unsupported format", error)
        ReaderUiState(
            ReaderUiState.Phase.FAILED,
            errorMessage = "This file type has no engine yet. This build reads " +
                "PDF, Word (DOCX/DOC), Excel (XLSX/XLS/CSV), PowerPoint (PPTX/PPT), " +
                "ODF, EPUB, CBZ comics, plain text (TXT/MD/RTF/HTML/XML/JSON), and images.",
        )
    } catch (error: DocumentAccessException) {
        Log.w(TAG, "open failed: document access", error)
        ReaderUiState(
            ReaderUiState.Phase.FAILED,
            errorMessage = "The file could not be read. It may have been moved, " +
                "or the source app revoked access.",
        )
    } catch (error: IOException) {
        Log.w(TAG, "open failed: io", error)
        ReaderUiState(
            ReaderUiState.Phase.FAILED,
            errorMessage = "The file could not be read from disk.",
        )
    } catch (error: Exception) {
        Log.w(TAG, "open failed: unexpected", error)
        ReaderUiState(
            ReaderUiState.Phase.FAILED,
            errorMessage = error.message ?: FALLBACK,
        )
    }

    private companion object {
        const val TAG = "ReaderOpen"
        const val FALLBACK = "Something went wrong opening this file."
    }
}
