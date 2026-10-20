package com.secure.reader.core.designsystem.component

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Tactile press feedback: any element compresses slightly under the finger,
 * the way a physical key would.
 *
 * The default spec is deliberately tight and non-bouncy — a scrolling list of
 * rows should feel like one continuous surface, and wobble at every tap cheapens
 * it. Reserve [PressSpringBouncy] for the single primary action.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    pressedScale: Float = 0.97f,
    spec: AnimationSpec<Float> = PressSpring,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val motion = LocalMotionEnabled.current
    // Hold the animated value as a plain State and read it *inside* the
    // graphicsLayer lambda: the layer re-reads it every frame without ever
    // recomposing the caller, so a press costs one layer update instead of a
    // recomposition of the pressed subtree on every animation frame.
    val scale = animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (motion) spec else snap(),
        label = "pressScale",
    )
    return this.then(
        Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    )
}
