package com.secure.reader.features.reader.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.secure.reader.core.designsystem.component.Motion
import com.secure.reader.core.designsystem.component.PageSkeleton
import com.secure.reader.core.designsystem.component.ReaderZoomBar
import com.secure.reader.core.designsystem.component.ShimmerBox
import com.secure.reader.core.designsystem.component.rememberShimmerProgress
import com.secure.reader.features.reader.domain.model.OpenedDocument
import com.secure.reader.features.reader.engine.pdf.BitmapPool
import com.secure.reader.features.reader.engine.pdf.PdfEngine
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_ZOOM = 6f
private const val TAP_ZOOM = 2.2f
private const val FIT_EPSILON = 0.02f

/** How long the zoom has to hold still before pages are re-rastered. */
private const val RASTER_SETTLE_MS = 200L

/** Overshoot past a render step that is not worth a re-raster. */
private const val RASTER_STEP_SLACK = 0.05f

private const val DEFAULT_PAGE_ASPECT = 1f / 1.414f

/** Discrete detents for the +/− controls; pinch stays continuous. */
private val ZOOM_STOPS = listOf(1f, 1.5f, 2f, 3f, 4f, 6f)

/**
 * Rendered bitmaps step up at these zoom levels instead of every 1%: crisp at
 * the detents the controls can reach, and bounded in between.
 */
private val RENDER_STEPS = listOf(1f, 1.5f, 2f, 3f)

/**
 * Closing a [PdfEngine] can wait on an in-flight raster job, so it is done off
 * the main thread. One process-wide serial lane keeps that work ordered without
 * ever blocking the UI thread on a page render.
 */
private val CloseDispatcher = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

/**
 * Fit-width paginated PDF reading with pinch zoom.
 *
 * Pages render off the main thread into viewport-sized pooled bitmaps and are
 * recycled the moment they leave the composition — memory stays flat no matter
 * the document length. Pinching scales the whole column on the GPU; while
 * zoomed, drags pan instead of scrolling.
 *
 * Resolution does not chase the gesture: pages re-render at the next
 * [RENDER_STEPS] size only once the zoom has settled ([RASTER_SETTLE_MS]). A
 * raster costs tens of milliseconds and tens of megabytes, so re-cutting it
 * mid-pinch would trade a smooth gesture for a marginally sharper frame that
 * the next pinch frame invalidates anyway.
 *
 * The +/− pill, double-tap and pinch all drive the same spring [scale], so the
 * gesture and the buttons never disagree about where the document sits.
 */
@Composable
fun PdfViewerScreen(
    document: OpenedDocument,
    modifier: Modifier = Modifier,
) {
    val pool = remember { BitmapPool() }
    val engineState = remember { mutableStateOf<PdfEngine?>(null) }
    val failed = remember { mutableStateOf(false) }

    LaunchedEffect(document.id) {
        engineState.value = withContext(Dispatchers.IO) {
            runCatching { PdfEngine.open(document.cachedFile, pool) }.getOrNull()
        }
        failed.value = engineState.value == null
    }
    DisposableEffect(document.id) {
        onDispose {
            val engine = engineState.value
            engineState.value = null
            if (engine != null) CloseDispatcher.launch { engine.close() }
        }
    }

    val engine = engineState.value
    when {
        failed.value -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "This PDF could not be rasterized.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        engine == null -> PdfLoadingSkeleton(modifier)
        else -> PdfPageList(engine = engine, modifier = modifier)
    }
}

@Composable
private fun PdfLoadingSkeleton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        PageSkeleton(
            modifier = Modifier.fillMaxSize(),
            pageHeight = 260.dp,
            label = "PREPARING PAGES",
        )
    }
}

@Composable
private fun PdfPageList(
    engine: PdfEngine,
    modifier: Modifier = Modifier,
) {
    val pageCount = remember(engine) { engine.pageCount }
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val targetWidthPx = with(density) { maxWidth.toPx().toInt() }
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }

        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val scope = rememberCoroutineScope()
        var zoomJob by remember { mutableStateOf<Job?>(null) }

        // The pinch callback writes `scale` every frame. Reading it directly in
        // composition would recompose the whole list at gesture rate; these
        // derived values only notify on a real change (a threshold crossing, a
        // whole percentage, a render step), so a pinch costs a handful of
        // recompositions instead of one per displayed frame.
        val zoomed by remember { derivedStateOf { scale > 1f + FIT_EPSILON } }
        val percent by remember { derivedStateOf { (scale * 100).roundToInt() } }

        // Rasterisation follows the zoom; it does not race it. `scale` is read
        // inside a coroutine rather than in composition, so a pinch never
        // recomposes the list, and the step only moves once the gesture has
        // been still for RASTER_SETTLE_MS — collectLatest restarts the wait on
        // every new scale, so a continuous pinch never triggers a raster.
        var rasterStep by remember { mutableFloatStateOf(RENDER_STEPS.first()) }
        LaunchedEffect(engine) {
            snapshotFlow { scale }.collectLatest { value ->
                delay(RASTER_SETTLE_MS)
                val step = RENDER_STEPS
                    .firstOrNull { it >= value - RASTER_STEP_SLACK }
                    ?: RENDER_STEPS.last()
                if (step != rasterStep) rasterStep = step
            }
        }

        fun clampOffset(candidate: Offset, currentScale: Float): Offset {
            val maxX = (viewportWidthPx * (currentScale - 1f)) / 2f
            val maxY = (viewportHeightPx * (currentScale - 1f)) / 2f
            return Offset(
                x = candidate.x.coerceIn(-maxX, maxX),
                y = candidate.y.coerceIn(-maxY, maxY),
            )
        }

        fun zoomTo(target: Float) {
            // A finger or a button press wins over any flight already in the air.
            zoomJob?.cancel()
            zoomJob = scope.launch {
                animate(
                    initialValue = scale,
                    targetValue = target,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ) { value, _ ->
                    scale = value
                    offset = clampOffset(offset, value)
                }
            }
        }

        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            zoomJob?.cancel()
            val newScale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
            if (newScale != scale) {
                scale = newScale
                // Pan must shrink with zoom, or the edge runs away from the finger.
                offset = clampOffset(offset, scale)
            }
            if (newScale > 1f + FIT_EPSILON) {
                offset = clampOffset(offset + panChange, scale)
            }
        }

        // Double-tap toggles between fit-width and detail zoom, spring-animated.
        val doubleTap = Modifier.pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    zoomTo(if (scale > 1f + FIT_EPSILON) 1f else TAP_ZOOM)
                },
            )
        }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    // Pinch scales the whole column around its centre; pan translates it.
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                        transformOrigin = TransformOrigin.Center
                    }
                    .then(doubleTap)
                    .transformable(state = transformState, canPan = { scale > 1f + FIT_EPSILON }),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 14.dp,
                    // Leave the last page's number clear of the zoom pill.
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                userScrollEnabled = !zoomed,
            ) {
                items(pageCount, key = { it }) { index ->
                    PdfPage(
                        engine = engine,
                        index = index,
                        total = pageCount,
                        targetWidthPx = targetWidthPx,
                        renderScale = rasterStep,
                    )
                }
            }

            // One-time gesture coaching, then the reading surface is left alone.
            var hintVisible by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(3400)
                hintVisible = false
            }
            AnimatedVisibility(
                visible = hintVisible,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
                enter = fadeIn(tween(Motion.ROUTINE_MS, easing = Motion.EaseOut)),
                exit = fadeOut(tween(Motion.ROUTINE_MS, easing = Motion.EaseOut)),
            ) {
                GestureHint()
            }

            ReaderZoomBar(
                percent = percent,
                canZoomIn = scale < MAX_ZOOM - FIT_EPSILON,
                canZoomOut = zoomed,
                onZoomIn = { zoomTo(ZOOM_STOPS.firstOrNull { it > scale + FIT_EPSILON } ?: MAX_ZOOM) },
                onZoomOut = { zoomTo(ZOOM_STOPS.lastOrNull { it < scale - FIT_EPSILON } ?: 1f) },
                onReset = { zoomTo(1f) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp),
            )
        }
    }
}

@Composable
private fun GestureHint(modifier: Modifier = Modifier) {
    Text(
        text = "PINCH OR DOUBLE-TAP TO ZOOM",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.9f),
                RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun PdfPage(
    engine: PdfEngine,
    index: Int,
    total: Int,
    targetWidthPx: Int,
    renderScale: Float,
    modifier: Modifier = Modifier,
) {
    val aspect = remember { mutableStateOf<Float?>(null) }
    val bitmap = remember { mutableStateOf<Bitmap?>(null) }
    val renderedWidth = remember { mutableIntStateOf(0) }

    LaunchedEffect(engine, index, targetWidthPx, renderScale) {
        if (targetWidthPx <= 0) return@LaunchedEffect

        // Resolved here, not in composition, so the box does not lay out at a
        // guessed ratio and then jump; a page reached twice reads the cache.
        var ratio = aspect.value
        if (ratio == null) {
            ratio = try {
                engine.aspectRatio(index)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            aspect.value = ratio
        }

        val requested = (targetWidthPx * renderScale).roundToInt()
        val width = PdfEngine.rasterWidth(requested, ratio ?: DEFAULT_PAGE_ASPECT)

        // The memory ceiling collapses the top of the step ladder onto one
        // width; re-rastering then would cost 16 MB for the same bitmap.
        if (bitmap.value != null && width == renderedWidth.value) return@LaunchedEffect

        val rendered = try {
            engine.render(index, width)
        } catch (cancelled: CancellationException) {
            // Scrolled away mid-raster: the engine already returned the buffer.
            throw cancelled
        } catch (_: Exception) {
            null
        }
        rendered?.let { page ->
            // Hand the previous resolution straight back to the pool.
            bitmap.value?.let { previous -> engine.release(previous) }
            bitmap.value = page
            renderedWidth.value = page.width
        }
    }

    DisposableEffect(engine, index) {
        onDispose {
            // Hand the bitmap straight back to the pool for the next visible page.
            bitmap.value?.let { engine.release(it) }
            bitmap.value = null
        }
    }

    val ratio = aspect.value ?: DEFAULT_PAGE_ASPECT
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(4.dp)
        // Paper sits on a surface: a real offset shadow lifts each page in light
        // mode, and in dark mode it reads as ambient occlusion rather than a grey
        // halo, so the page edge stays crisp without fighting the background.
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val elevation = if (dark) 1.dp else 2.5.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .shadow(elevation = elevation, shape = shape, clip = true)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            contentAlignment = Alignment.Center,
        ) {
            val current = bitmap.value
            if (current != null && !current.isRecycled) {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = "Page ${index + 1} of $total",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val shimmer = rememberShimmerProgress()
                ShimmerBox(progress = shimmer, modifier = Modifier.fillMaxSize(), cornerRadius = 4.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${index + 1} / $total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
