package com.secure.reader.features.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secure.reader.core.designsystem.component.ErrorStateBlock
import com.secure.reader.core.designsystem.component.Hairline
import com.secure.reader.core.file.Engine
import com.secure.reader.core.util.formatFileSize

@Composable
fun ReaderScreen(
    state: ReaderUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Hero shared-element modifier: the filename flies in from the library row. */
    titleModifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        ReaderTopBar(
            state = state,
            onBack = onBack,
            titleModifier = titleModifier,
        )
        Hairline()

        when (state.phase) {
            ReaderUiState.Phase.LOADING -> Unit
            ReaderUiState.Phase.FAILED -> ErrorStateBlock(
                title = "Can't open document",
                body = state.errorMessage ?: "The document could not be opened.",
                actionText = "Try again",
                onAction = onRetry,
                modifier = Modifier.padding(20.dp),
            )
            ReaderUiState.Phase.READY -> {
                val document = state.document
                if (document != null) {
                    when (document.format.engine) {
                        Engine.PDF -> PdfViewerScreen(
                            document = document,
                            modifier = Modifier.weight(1f),
                        )
                        Engine.IMAGE -> ImageViewerScreen(
                            document = document,
                            modifier = Modifier.weight(1f),
                        )
                        Engine.OFFICE_WEB -> OfficeViewerScreen(
                            document = document,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    state: ReaderUiState,
    onBack: () -> Unit,
    titleModifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back to library",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.document?.fileName ?: "Document",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (state.document != null) titleModifier else Modifier,
            )
            state.document?.let { document ->
                Text(
                    text = "${document.format.displayTag(document.fileName)}" +
                        " · ${formatFileSize(document.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val TITLE_HERO_KEY = "document-title"
