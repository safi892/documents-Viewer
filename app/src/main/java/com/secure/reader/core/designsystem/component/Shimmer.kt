package com.secure.reader.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Where a resting (motion-disabled) skeleton sits: mid-band, not a blank fill. */
private const val SHIMMER_REST = 0.45f

private const val SWEEP_MS = 1500

/**
 * One shimmer clock for a whole screen.
 *
 * Every skeleton block sharing this state animates in lockstep from a single
 * infinite transition, so a loading screen with five placeholders costs one
 * animation driver instead of five. The value is read only in the draw phase, so
 * a shimmering screen performs zero recompositions while it waits.
 */
@Composable
fun rememberShimmerProgress(): State<Float> {
    if (!LocalMotionEnabled.current) {
        return remember { mutableFloatStateOf(SHIMMER_REST) }
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SWEEP_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )
}

/**
 * Skeleton block used for loading states that mirror the layout about to appear
 * (taste rule: no generic spinners).
 *
 * A narrow band sweeps across the resting fill instead of the whole block
 * pulsing: it reads as a page being printed rather than a box blinking, and it
 * costs one draw call per frame instead of one recomposition per frame.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    cornerRadius: Dp = 6.dp,
    progress: State<Float>? = null,
) {
    val state = progress ?: rememberShimmerProgress()
    val base = MaterialTheme.colorScheme.surfaceVariant
    val sheen = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .drawBehind {
                drawRect(color = base)
                if (size.width <= 0f || size.height <= 0f) return@drawBehind
                val t = state.value
                val band = size.width * 0.55f
                val travel = size.width + band
                val start = -band + travel * t
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(base, sheen, base),
                        start = Offset(start, 0f),
                        end = Offset(start + band, 0f),
                    ),
                )
            },
    )
}
