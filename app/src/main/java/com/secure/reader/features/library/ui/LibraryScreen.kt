package com.secure.reader.features.library.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.secure.reader.core.designsystem.component.EmptyStateBlock
import com.secure.reader.core.designsystem.component.ErrorStateBlock
import com.secure.reader.core.designsystem.component.Hairline
import com.secure.reader.core.designsystem.component.ShimmerBox
import com.secure.reader.core.designsystem.component.TactileButton
import com.secure.reader.features.library.domain.model.RecentDocument
import com.secure.reader.features.library.ui.components.RecentDocumentRow

private const val SKELETON_ROWS = 5

/**
 * The library list is lazy on purpose: a long recents list must only pay for
 * the rows on screen, and the row-entrance cascade must not become a
 * compose-everything-then-animate-everything cost.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onOpenFile: () -> Unit,
    onOpenDocument: (RecentDocument) -> Unit,
    onDeleteDocument: (RecentDocument) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Hero modifier applied to the row currently opening into the reader. */
    titleModifier: (RecentDocument) -> Modifier = { Modifier },
) {
    val readyCount = (state as? LibraryUiState.Ready)?.documents?.size
    var revealed by remember(readyCount) { mutableStateOf(false) }
    LaunchedEffect(readyCount) { revealed = readyCount != null }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 40.dp),
    ) {
        item(key = "header") {
            // Asymmetric header: left-aligned wordmark, mono system label at the right edge.
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Universal Reader", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "ON-DEVICE DOCUMENT ENGINE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "PDF · OFFICE · EPUB · TEXT · COMIC",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(26.dp))

            TactileButton(
                text = "Open file",
                icon = Icons.Outlined.FolderOpen,
                onClick = onOpenFile,
            )

            Spacer(Modifier.height(28.dp))
            Hairline()
        }

        when (state) {
            LibraryUiState.Loading -> item(key = "loading") {
                // One shared shimmer clock for all rows: five independent infinite
                // transitions would keep five clocks ticking for the same wobble.
                LibraryLoading()
            }

            LibraryUiState.Empty -> item(key = "empty") {
                LibraryEmpty()
            }

            is LibraryUiState.Ready -> {
                item(key = "count") {
                    Text(
                        "${state.documents.size} " +
                            if (state.documents.size == 1) "DOCUMENT" else "DOCUMENTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp),
                    )
                }
                itemsIndexed(
                    items = state.documents,
                    key = { _, document -> document.id },
                ) { index, document ->
                    val delay = index.coerceAtMost(10) * 45
                    AnimatedVisibility(
                        visible = revealed,
                        enter = fadeIn(tween(280, delayMillis = delay)) +
                            slideInVertically(tween(320, delayMillis = delay)) { it / 3 },
                        exit = fadeOut(tween(160)),
                    ) {
                        RecentDocumentRow(
                            document = document,
                            onClick = { onOpenDocument(document) },
                            onDelete = { onDeleteDocument(document) },
                            titleModifier = titleModifier(document),
                        )
                    }
                }
            }

            is LibraryUiState.Failed -> item(key = "failed") {
                LibraryFailed(message = state.message, onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun LibraryLoading() {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        repeat(SKELETON_ROWS) { index ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShimmerBox(modifier = Modifier.width(44.dp), height = 44.dp, cornerRadius = 10.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    ShimmerBox(
                        modifier = Modifier.width(if (index % 2 == 0) 190.dp else 140.dp),
                        height = 14.dp,
                    )
                    Spacer(Modifier.height(8.dp))
                    ShimmerBox(modifier = Modifier.width(96.dp), height = 10.dp)
                }
            }
            Hairline()
        }
    }
}

@Composable
private fun LibraryEmpty() {
    // The header already carries the primary action; a second identical button
    // would just be the same affordance twice.
    EmptyStateBlock(
        icon = Icons.Outlined.FolderOpen,
        title = "No documents yet",
        body = "Open a PDF, Word, Excel or PowerPoint file, an OpenDocument file, " +
            "an EPUB book, a CBZ comic, plain text (TXT, MD, RTF, HTML, XML, JSON) " +
            "or an image. Everything is parsed on this device — nothing is uploaded.",
    )
}

@Composable
private fun LibraryFailed(message: String, onRetry: () -> Unit) {
    ErrorStateBlock(
        title = "Library unavailable",
        body = message,
        actionText = "Retry",
        onAction = onRetry,
    )
}
