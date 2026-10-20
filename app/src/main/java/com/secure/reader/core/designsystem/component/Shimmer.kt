package com.secure.reader.core.designsystem.component

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One shimmer clock for a whole screen.
 *
 * Every skeleton block sharing this state animates in lockstep from a single
 * infinite transition, so a loading screen with five placeholders costs one
 * recomposition driver instead of five — measurably cheaper on low-end devices.
 */
@Composable
fun rememberShimmerProgress(): State<Float> {
    val transition = rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmerProgress",
    )
}

/**
 * Shimmering skeleton block used for loading states that mirror the layout
 * that is about to appear (taste rule: no generic spinners).
 *
 * Pass the shared [rememberShimmerProgress] clock to keep a multi-block
 * skeleton on one animation; the default spins its own clock for one-offs.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    cornerRadius: Dp = 6.dp,
    progress: State<Float>? = null,
) {
    val localProgress by (progress ?: rememberShimmerProgress())
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                lerp(
                    colorScheme.surfaceVariant,
                    colorScheme.surfaceContainerHigh,
                    localProgress,
                ),
            ),
    )
}
