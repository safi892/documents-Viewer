package com.secure.reader.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Tactile press feedback: any element compresses slightly under the finger,
 * the way a physical key would. Spring physics, never linear easing.
 */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    // Hold the animated value as a plain State and read it *inside* the
    // graphicsLayer lambda: the layer re-reads it every frame without ever
    // recomposing the caller, so a press costs one layer update instead of a
    // recomposition of the pressed subtree on every animation frame.
    val scale = animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    return this.then(
        Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    )
}
