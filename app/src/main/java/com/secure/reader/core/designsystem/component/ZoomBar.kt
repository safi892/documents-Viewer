package com.secure.reader.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val ZoomBarShape = RoundedCornerShape(999.dp)

/**
 * The reader's zoom control: one compact, always-visible pill with zoom-out, a
 * live percentage that doubles as "fit to width", and zoom-in.
 *
 * Every touch is a 48dp target with a spring press and a light haptic tick, so
 * stepping the zoom reads as a detent rather than a button. The readout is the
 * only place the zoom number lives, so the surface never shows the same value
 * twice.
 */
@Composable
fun ReaderZoomBar(
    percent: Int,
    canZoomIn: Boolean,
    canZoomOut: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = ZoomBarShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZoomStep(
                icon = Icons.Rounded.Remove,
                description = "Zoom out",
                enabled = canZoomOut,
                onClick = onZoomOut,
            )
            ZoomReadout(percent = percent, onReset = onReset)
            ZoomStep(
                icon = Icons.Rounded.Add,
                description = "Zoom in",
                enabled = canZoomIn,
                onClick = onZoomIn,
            )
        }
    }
}

@Composable
private fun ZoomStep(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            )
            .pressScale(interactionSource)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ZoomReadout(percent: Int, onReset: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 58.dp)
            .height(48.dp)
            .clickable(interactionSource = interactionSource, indication = null) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onReset()
            }
            .pressScale(interactionSource)
            .semantics {
                contentDescription = "Zoom $percent percent. Activate to fit to width."
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
