package com.secure.reader.features.library.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.secure.reader.core.designsystem.component.EmptyStateBlock
import com.secure.reader.core.designsystem.component.ErrorStateBlock
import com.secure.reader.core.designsystem.component.Hairline
import com.secure.reader.core.designsystem.component.ShimmerBox
import com.secure.reader.core.designsystem.component.SpecLegend
import com.secure.reader.core.designsystem.component.TactileButton
import com.secure.reader.core.designsystem.component.rememberShimmerProgress
import com.secure.reader.core.util.formatFileSize
import com.secure.reader.features.library.domain.model.RecentDocument
import com.secure.reader.features.library.ui.components.RecentDocumentRow

private const val SKELETON_ROWS = 6

private val Legend = listOf(
    "DOCUMENTS" to "PDF · DOCX · XLSX · PPTX · ODT · ODS · ODP",
    "BOOKS" to "EPUB · CBZ",
    "TEXT" to "TXT · MD · RTF · HTML · XML · JSON · CSV",
    "IMAGES" to "JPG · PNG · WEBP · GIF · HEIC",
)

/**
 * The library is lazy on purpose: a long recents list only pays for the rows on
 * screen.
 *
 * There is deliberately no staggered row-entrance animation. A cascade over a
 * list means one animation object, one layer and one delay per row — the single
 * most expensive thing this screen could do, for a flourish nobody asked for.
 * Insertions, removals and reordering animate through [animateItem] instead, and
 * the app's one authored moment stays where it belongs: the hand-off into the
 * reader.
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
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 40.dp),
    ) {
        item(key = "header") {
            LibraryHeader(state = state, onOpenFile = onOpenFile)
        }

        when (state) {
            LibraryUiState.Loading -> item(key = "loading") {
                LibraryLoading()
            }

            LibraryUiState.Empty -> item(key = "empty") {
                LibraryEmpty()
            }

            is LibraryUiState.Ready -> items(
                items = state.documents,
                key = { document -> document.id },
            ) { document ->
                RecentDocumentRow(
                    document = document,
                    onClick = { onOpenDocument(document) },
                    onDelete = { onDeleteDocument(document) },
                    titleModifier = titleModifier(document),
                    modifier = Modifier.animateItem(),
                )
            }

            is LibraryUiState.Failed -> item(key = "failed") {
                LibraryFailed(message = state.message, onRetry = onRetry)
            }
        }
    }
}

/**
 * Wordmark, the live facts about the shelf, and the one action worth taking.
 *
 * No eyebrow above the heading and no repeated count row: the number belongs in
 * the line under the wordmark, where it is read once instead of twice.
 */
@Composable
private fun LibraryHeader(
    state: LibraryUiState,
    onOpenFile: () -> Unit,
) {
    val documents = (state as? LibraryUiState.Ready)?.documents.orEmpty()
    val facts = remember(documents) {
        if (documents.isEmpty()) {
            ""
        } else {
            val total = documents.sumOf { it.sizeBytes }
            "${documents.size} ${if (documents.size == 1) "DOCUMENT" else "DOCUMENTS"}" +
                " · ${formatFileSize(total)}"
        }
    }

    Column {
        Text("Universal Reader", style = MaterialTheme.typography.headlineMedium)
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            Text(
                text = facts,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
        TactileButton(
            text = "Open file",
            icon = Icons.Outlined.FolderOpen,
            onClick = onOpenFile,
        )
        Spacer(Modifier.height(30.dp))
        Hairline()
    }
}

@Composable
private fun LibraryLoading() {
    val shimmer = rememberShimmerProgress()
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Spacer(Modifier.height(18.dp))
        repeat(SKELETON_ROWS) { index ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShimmerBox(
                    progress = shimmer,
                    modifier = Modifier.width(40.dp),
                    height = 12.dp,
                    cornerRadius = 4.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    ShimmerBox(
                        modifier = Modifier.width(if (index % 2 == 0) 196.dp else 148.dp),
                        height = 13.dp,
                        progress = shimmer,
                    )
                    Spacer(Modifier.height(9.dp))
                    ShimmerBox(
                        modifier = Modifier.width(94.dp),
                        height = 9.dp,
                        progress = shimmer,
                    )
                }
            }
            Hairline()
        }
    }
}

@Composable
private fun LibraryEmpty() {
    EmptyStateBlock(
        title = "No documents yet",
        body = "Everything is opened on this device. Nothing is uploaded.",
        legend = { SpecLegend(entries = Legend) },
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
