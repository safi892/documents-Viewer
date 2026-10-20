package com.secure.reader.core.designsystem.component

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one loading composition in the app: a page worth of paper, the lines that
 * will be on it, and — when the format has a named engine — a live status line.
 *
 * It mirrors the document that is about to appear instead of being a generic
 * slab, so the reveal reads as the page settling rather than a box vanishing.
 */
@Composable
fun PageSkeleton(
    modifier: Modifier = Modifier,
    pageHeight: Dp = 300.dp,
    label: String? = null,
    lines: Int = 3,
) {
    val shimmer = rememberShimmerProgress()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShimmerBox(
            progress = shimmer,
            modifier = Modifier.fillMaxWidth(),
            height = pageHeight,
            cornerRadius = 6.dp,
        )
        Spacer(Modifier.height(2.dp))
        if (label != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot()
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        repeat(lines) { line ->
            val fraction = when (line) {
                0 -> 0.82f
                1 -> 0.58f
                else -> 0.34f
            }
            ShimmerBox(
                progress = shimmer,
                modifier = Modifier.fillMaxWidth(fraction),
                height = 11.dp,
            )
        }
    }
}

/**
 * The only "something is alive" signal in the loading states: a soft pulse on a
 * status dot. Read inside `graphicsLayer`, so the animation never recomposes.
 */
@Composable
fun PulseDot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val motion = LocalMotionEnabled.current
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = if (motion) 0.5f else 0.85f,
        targetValue = if (motion) 1f else 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(760, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Box(
        modifier = modifier
            .size(6.dp)
            .graphicsLayer {
                scaleX = pulse
                scaleY = pulse
                alpha = pulse
            }
            .background(color, RoundedCornerShape(2.dp)),
    )
}
