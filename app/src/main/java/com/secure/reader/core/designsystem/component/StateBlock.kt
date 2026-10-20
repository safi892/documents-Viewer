package com.secure.reader.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val ButtonShape = RoundedCornerShape(12.dp)

/**
 * The single primary action. Tactile press, accent fill, no glow.
 */
@Composable
fun TactileButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        interactionSource = interactionSource,
        modifier = modifier.pressScale(interactionSource),
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Secondary, hairline-outlined action (retries, dismissals).
 */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val outline = MaterialTheme.colorScheme.outlineVariant
    Surface(
        onClick = onClick,
        shape = ButtonShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        interactionSource = interactionSource,
        modifier = modifier
            .pressScale(interactionSource)
            .border(1.dp, outline, ButtonShape),
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 44.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Composed empty state: icon, concrete copy, and the action that fills it.
 * No cards — composition and negative space only.
 */
@Composable
fun EmptyStateBlock(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 56.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}

/**
 * Inline error block: concrete cause + recovery action underneath.
 */
@Composable
fun ErrorStateBlock(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val errorColor = MaterialTheme.colorScheme.error
    Column(
        modifier = modifier.padding(vertical = 40.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(errorColor, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = errorColor)
        }
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        if (actionText != null && onAction != null) {
            GhostButton(text = actionText, onClick = onAction)
        }
    }
}

/**
 * One-pixel rule used instead of cards to group content (taste rule:
 * anti-card overuse at airy densities).
 */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
