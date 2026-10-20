package com.secure.reader.features.library.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secure.reader.core.designsystem.component.Hairline
import com.secure.reader.core.designsystem.component.pressScale
import com.secure.reader.core.util.formatFileSize
import com.secure.reader.features.library.domain.model.RecentDocument
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(document.lastAccessed))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .pressScale(interactionSource)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormatGlyph(tag = document.format.displayTag(document.fileName))

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
            Spacer(Modifier.size(4.dp))
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
    Hairline()
}

@Composable
private fun FormatGlyph(tag: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
