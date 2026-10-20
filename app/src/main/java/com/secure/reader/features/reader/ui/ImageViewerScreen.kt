package com.secure.reader.features.reader.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.secure.reader.core.designsystem.component.ErrorStateBlock
import com.secure.reader.core.designsystem.component.ReaderZoomBar
import com.secure.reader.core.designsystem.component.ShimmerBox
import com.secure.reader.core.designsystem.component.rememberShimmerProgress
import com.secure.reader.features.reader.domain.model.OpenedDocument
import com.secure.reader.features.reader.engine.image.ImageDecode
import com.secure.reader.features.reader.engine.image.ImageRenderer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val MAX_IMAGE_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Discrete detents for the +/− controls; pinch stays continuous. */
private val IMAGE_ZOOM_STOPS = listOf(1f, 1.5f, 2f, 3f, 4f, 6f, 8f)

/**
 * How much larger than the viewport the bitmap is decoded. 3× keeps the first
 * few zoom stops genuinely sharp; beyond that the pixels are real ones merely
 * magnified, which is honest and bounded. The renderer caps the pixel budget.
 */
private const val DECODE_RENDER_MULTIPLIER = 3f

private sealed interface ImageState {
    data object Loading : ImageState
    data class Ready(val bitmap: Bitmap) : ImageState
    data class Failed(val reason: String) : ImageState
}

/**
 * Single-image reader: photos, scans and screenshots.
 *
 * Decoding runs off the main thread and is bounded by [ImageRenderer]; the
 * bitmap is recycled the moment the screen leaves composition, so paging through
 * a folder of 12 MP photos holds exactly one image in memory at a time.
 *
 * Pinch, double-tap and the +/− pill all drive one [PanZoomState], which is why
 * the gesture and the buttons can never disagree about where the image sits.
 */
@Composable
fun ImageViewerScreen(
    document: OpenedDocument,
    modifier: Modifier = Modifier,
) {
    var state by remember(document.id) { mutableStateOf<ImageState>(ImageState.Loading) }

    DisposableEffect(document.id) {
        onDispose {
            // Nothing paints this bitmap after the screen leaves composition, so
            // an explicit recycle is safe here and returns the memory immediately.
            (state as? ImageState.Ready)?.bitmap?.recycle()
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }

        LaunchedEffect(document.id, viewportWidthPx, viewportHeightPx) {
            if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return@LaunchedEffect
            // Re-decoding when the viewport changes (rotation) is what keeps the
            // pixels matched to the space we actually have.
            state = ImageState.Loading
            val targetWidthPx =
                (maxOf(viewportWidthPx, viewportHeightPx) * DECODE_RENDER_MULTIPLIER).toInt()
            state = when (val decoded = ImageRenderer.decode(document.cachedFile, targetWidthPx)) {
                is ImageDecode.Success -> ImageState.Ready(decoded.bitmap)
                is ImageDecode.Failure -> ImageState.Failed(decoded.reason)
            }
        }

        when (val current = state) {
            ImageState.Loading -> ImageLoading(modifier = Modifier.fillMaxSize())

            is ImageState.Failed -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                ErrorStateBlock(title = "Can't show this image", body = current.reason)
            }

            is ImageState.Ready -> ZoomableImage(
                bitmap = current.bitmap,
                description = document.fileName,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    bitmap: Bitmap,
    description: String,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
) {
    val haptics = LocalHapticFeedback.current
    val zoomState = remember { PanZoomState(MAX_IMAGE_ZOOM) }
    val scope = rememberCoroutineScope()
    var zoomJob by remember { mutableStateOf<Job?>(null) }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    // Where the image actually paints at scale 1 once ContentScale.Fit letterboxes it.
    val fitted = remember(bitmap, viewportWidthPx, viewportHeightPx) {
        val bitmapWidth = bitmap.width.toFloat()
        val bitmapHeight = bitmap.height.toFloat()
        if (bitmapWidth <= 0f || bitmapHeight <= 0f) {
            Size.Zero
        } else {
            val fit = minOf(viewportWidthPx / bitmapWidth, viewportHeightPx / bitmapHeight)
            Size(bitmapWidth * fit, bitmapHeight * fit)
        }
    }

    SideEffect {
        zoomState.viewportSize = Size(viewportWidthPx, viewportHeightPx)
        zoomState.contentSize = fitted
        // A re-decode (rotation) can leave the pan further out than the new
        // bounds allow.
        zoomState.settle()
    }

    // Derived, so a pinch invalidates the composition only when the value it
    // shows meaningfully changes — not once per frame.
    val zoomed by remember { derivedStateOf { zoomState.scale > 1f + ZOOM_FIT_EPSILON } }
    val percent by remember { derivedStateOf { zoomPercent(zoomState.scale) } }
    val canZoomIn by remember { derivedStateOf { zoomState.scale < MAX_IMAGE_ZOOM - ZOOM_FIT_EPSILON } }

    fun zoomTo(target: Float) {
        // A finger or a button press always wins over a flight in the air.
        zoomJob?.cancel()
        zoomJob = scope.launch { zoomState.animateTo(target) }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoomJob?.cancel()
        zoomState.applyGesture(zoomChange, panChange)
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoomState.scale
                    scaleY = zoomState.scale
                    translationX = zoomState.offset.x
                    translationY = zoomState.offset.y
                    transformOrigin = TransformOrigin.Center
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val target = if (zoomState.scale > 1f + ZOOM_FIT_EPSILON) 1f else DOUBLE_TAP_ZOOM
                        zoomTo(target)
                    })
                }
                .transformable(
                    state = transformState,
                    canPan = { zoomState.scale > 1f + ZOOM_FIT_EPSILON },
                ),
        ) {
            Image(
                bitmap = imageBitmap,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        ReaderZoomBar(
            percent = percent,
            canZoomIn = canZoomIn,
            canZoomOut = zoomed,
            onZoomIn = { zoomTo(nextZoomStop(zoomState.scale, IMAGE_ZOOM_STOPS, MAX_IMAGE_ZOOM)) },
            onZoomOut = { zoomTo(previousZoomStop(zoomState.scale, IMAGE_ZOOM_STOPS) ?: 1f) },
            onReset = { zoomTo(1f) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
        )
    }
}

@Composable
private fun ImageLoading(modifier: Modifier = Modifier) {
    val shimmer = rememberShimmerProgress()
    Column(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ShimmerBox(
            progress = shimmer,
            modifier = Modifier.fillMaxWidth(),
            height = 320.dp,
            cornerRadius = 8.dp,
        )
        ShimmerBox(progress = shimmer, modifier = Modifier.width(140.dp), height = 12.dp)
        Text(
            text = "DECODING IMAGE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
