package com.secure.reader.core.designsystem.component

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val ButtonShape = RoundedCornerShape(12.dp)

/**
 * The single primary action. Tactile press, accent fill, no glow.
 *
 * This is the one control allowed a little spring: it is the only place the app
 * asks the user to commit to something.
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
        modifier = modifier.pressScale(interactionSource, spec = PressSpringBouncy),
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
                .defaultMinSize(minHeight = 48.dp)
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
 * Composed empty state: the product's own object (a page with nothing on it),
 * the facts stated as a spec sheet rather than a paragraph, and negative space.
 * No cards.
 */
@Composable
fun EmptyStateBlock(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    legend: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(vertical = 44.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EmptyPageMark()
        Spacer(Modifier.height(2.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
        }
        if (legend != null) {
            Spacer(Modifier.height(2.dp))
            legend()
        }
    }
}

/**
 * A blank page, drawn rather than illustrated: one hairline rectangle and a few
 * rules that stop short. It is the app's subject in its emptiest state, and it
 * costs four draw calls.
 */
@Composable
private fun EmptyPageMark(modifier: Modifier = Modifier) {
    val stroke = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
    Canvas(
        modifier = modifier
            .size(width = 52.dp, height = 68.dp),
    ) {
        val corner = CornerRadius(10.dp.toPx())
        drawRoundRect(
            color = stroke,
            topLeft = Offset(0f, 0f),
            size = Size(size.width, size.height),
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx()),
        )
        val inset = size.width * 0.2f
        val ruleWidth = size.width - inset * 2f
        val ruleHeight = 1.5.dp.toPx()
        val gap = size.height * 0.115f
        val lengths = listOf(1f, 1f, 0.72f, 1f, 0.86f, 0.42f)
        lengths.forEachIndexed { index, fraction ->
            val y = size.height * 0.2f + gap * index
            drawRoundRect(
                color = ink,
                topLeft = Offset(inset, y),
                size = Size(ruleWidth * fraction, ruleHeight),
                cornerRadius = CornerRadius(ruleHeight / 2f),
            )
        }
    }
}

/**
 * The formats the app actually renders, as a spec sheet: a mono label column and
 * a mono value column. Facts, not marketing — and far lighter to read than the
 * run-on sentence it replaces.
 */
@Composable
fun SpecLegend(
    entries: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        entries.forEach { (label, value) ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(86.dp),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
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
        modifier = modifier.padding(vertical = 40.dp, horizontal = 4.dp),
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
