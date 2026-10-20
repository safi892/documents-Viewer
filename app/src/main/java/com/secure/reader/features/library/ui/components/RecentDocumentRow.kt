package com.secure.reader.features.library.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secure.reader.core.designsystem.component.pressScale
import com.secure.reader.core.util.formatFileSize
import com.secure.reader.features.library.domain.model.RecentDocument
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `SimpleDateFormat` is not thread-safe, so each thread gets its own. Rows are
 * composed on the main thread, and this keeps scrolling through a long library
 * from allocating a formatter per row.
 */
private val DateFormat = ThreadLocal.withInitial {
    SimpleDateFormat("d MMM yyyy", Locale.getDefault())
}

/**
 * One document: a mono format tag, the name, and the two facts that actually
 * help identify a file (size, when it was last opened).
 *
 * The tag is set in the accent colour instead of a bordered chip — a chip per row
 * is a box per row, and the colour carries the identity in less space. The
 * separator is drawn by this row rather than composed as a sibling, which saves a
 * layout node per document.
 */
@Composable
fun RecentDocumentRow(
    document: RecentDocument,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** Hero shared-element modifier for the filename while this row opens. */
    titleModifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val dateLabel = remember(document.lastAccessed) {
        DateFormat.get().format(Date(document.lastAccessed))
    }
    val rule = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = 1.dp.toPx()
                val y = size.height - stroke / 2f
                drawLine(rule, Offset(0f, y), Offset(size.width, y), stroke)
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .pressScale(interactionSource, pressedScale = 0.985f)
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = document.format.displayTag(document.fileName),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(40.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
        ) {
            Text(
                text = document.fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                minLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = titleModifier,
            )
            Spacer(Modifier.size(5.dp))
            Text(
                text = "${formatFileSize(document.sizeBytes)} · $dateLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove ${document.fileName} from library",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
