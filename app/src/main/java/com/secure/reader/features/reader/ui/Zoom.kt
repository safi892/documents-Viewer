package com.secure.reader.features.reader.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.roundToInt

/** How far from "fit" a gesture must land before the reader calls it zoomed. */
internal const val ZOOM_FIT_EPSILON = 0.02f

/**
 * Pan/zoom state for a single pannable surface.
 *
 * The state lives in one remembered object rather than in composable locals so
 * the gesture lambda can never capture a stale viewport or content size: those
 * are plain properties of this object, refreshed from a `SideEffect` after every
 * composition, and the lambda only ever reads `this`.
 *
 * [scale] is backed by snapshot state so it can be read *inside* a
 * `graphicsLayer {}` block — that read is deferred to the draw phase and costs
 * no recomposition, which is what keeps a pinch at display refresh rate. Nothing
 * in the composition may read [scale] directly; derive a threshold or a label
 * with `derivedStateOf` so the composition only invalidates when the meaning
 * changes, not on every animation frame.
 */
internal class PanZoomState(private val maxZoom: Float) {

    var scale: Float by mutableFloatStateOf(1f)
        private set

    var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    /** Size the content paints at scale 1, after letterboxing into the viewport. */
    var contentSize: Size = Size.Zero

    var viewportSize: Size = Size.Zero

    fun applyGesture(zoomChange: Float, panChange: Offset) {
        val next = (scale * zoomChange).coerceIn(1f, maxZoom)
        if (next != scale) {
            scale = next
            offset = clamp(offset, next)
        }
        // Pan is meaningful only once the content overhangs the viewport.
        if (next > 1f + ZOOM_FIT_EPSILON) {
            offset = clamp(offset + panChange, next)
        }
    }

    /** Springs to [target]; cancelling any flight already in the air is the caller's job. */
    suspend fun animateTo(target: Float) {
        animate(
            initialValue = scale,
            targetValue = target.coerceIn(1f, maxZoom),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ) { value, _ ->
            scale = value
            offset = clamp(offset, value)
        }
    }

    /** Re-applies the pan limit after the scale changed underneath us. */
    fun settle() {
        offset = clamp(offset, scale)
    }

    /**
     * Pan is capped at the overhang of the scaled content over the viewport, so
     * letterboxed margins never move and the content can never be flung off the
     * screen — the single most common complaint about home-grown image viewers.
     */
    private fun clamp(candidate: Offset, atScale: Float): Offset {
        val maxX = ((contentSize.width * atScale) - viewportSize.width).coerceAtLeast(0f) / 2f
        val maxY = ((contentSize.height * atScale) - viewportSize.height).coerceAtLeast(0f) / 2f
        return Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY),
        )
    }
}

/** Next detent above [scale], or null at the ceiling. */
internal fun nextZoomStop(scale: Float, stops: List<Float>, max: Float): Float =
    stops.firstOrNull { it > scale + ZOOM_FIT_EPSILON } ?: max

/** Next detent below [scale], or null at the floor. */
internal fun previousZoomStop(scale: Float, stops: List<Float>): Float? =
    stops.lastOrNull { it < scale - ZOOM_FIT_EPSILON }

internal fun zoomPercent(scale: Float): Int = (scale * 100f).roundToInt()
