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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
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
import kotlin.math.abs
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

/** Gutter between the page edge and the screen edge at fit width. */
private val PAGE_MARGIN = 12.dp

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
 * Zoom changes the page *width* in layout rather than scaling a layer: pages
 * grow wider and taller inside the vertical list, so the document keeps its
 * real scroll range at every zoom and native scroll physics stay intact.
 * Horizontal pan is a draw-time translation of the page, clamped so that the
 * paper edges stop exactly at the screen edges — the page can never be pushed
 * off the viewport, and at fit width it is exactly centred.
 *
 * Pages render off the main thread into viewport-sized pooled bitmaps and are
 * recycled the moment they leave the composition — memory stays flat no matter
 * the document length. Resolution does not chase the gesture: pages re-render
 * at the next [RENDER_STEPS] size only once the zoom has settled
 * ([RASTER_SETTLE_MS]). A raster costs tens of milliseconds and tens of
 * megabytes, so re-cutting it mid-pinch would trade a smooth gesture for a
 * marginally sharper frame that the next pinch frame invalidates anyway.
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
        val viewportWidthPx = constraints.maxWidth.toFloat()
        val contentWidthPx = (viewportWidthPx - with(density) { (PAGE_MARGIN * 2).toPx() })
            .coerceAtLeast(1f)

        // Scale is layout state, not a layer transform: the page is genuinely
        // that wide, so the horizontal pan range is derived from real edges.
        val scale = remember { mutableFloatStateOf(1f) }
        val panX = remember { mutableFloatStateOf(0f) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var zoomJob by remember { mutableStateOf<Job?>(null) }

        // The pinch writes `scale` every frame. Reading it directly in
        // composition would recompose the whole list at gesture rate; these
        // derived values only notify on a real change (a threshold crossing, a
        // whole percentage, a render step), so a pinch costs a handful of
        // recompositions instead of one per displayed frame.
        val zoomed by remember { derivedStateOf { scale.floatValue > 1f + FIT_EPSILON } }
        val percent by remember { derivedStateOf { (scale.floatValue * 100f).roundToInt() } }
        val canZoomIn by remember { derivedStateOf { scale.floatValue < MAX_ZOOM - FIT_EPSILON } }

        // Rasterisation follows the zoom; it does not race it. `scale` is read
        // inside a coroutine rather than in composition, so a pinch never
        // recomposes the list, and the step only moves once the gesture has
        // been still for RASTER_SETTLE_MS — collectLatest restarts the wait on
        // every new scale, so a continuous pinch never triggers a raster.
        var rasterStep by remember { mutableFloatStateOf(RENDER_STEPS.first()) }
        LaunchedEffect(engine) {
            snapshotFlow { scale.floatValue }.collectLatest { value ->
                delay(RASTER_SETTLE_MS)
                val step = RENDER_STEPS
                    .firstOrNull { it >= value - RASTER_STEP_SLACK }
                    ?: RENDER_STEPS.last()
                if (step != rasterStep) rasterStep = step
            }
        }

        // The page is wider than the viewport only while zoomed; when it is,
        // its centre may travel until an edge meets a screen edge. At fit width
        // the allowance is zero, which pins the page back to centre.
        fun clampPan(candidate: Float, forScale: Float): Float {
            val maxPan = ((contentWidthPx * forScale) - viewportWidthPx) / 2f
            return if (maxPan <= 0f) 0f else candidate.coerceIn(-maxPan, maxPan)
        }

        fun zoomTo(target: Float, focalX: Float = viewportWidthPx / 2f) {
            // A finger or a button press wins over any flight already in the air.
            zoomJob?.cancel()
            val startScale = scale.floatValue
            val startPan = panX.floatValue
            if (abs(target - startScale) < 0.001f) return
            // Keep the page point under the focal x pinned while the width
            // changes: pan' = pan * k + anchor * (1 - k).
            val anchor = focalX - viewportWidthPx / 2f
            zoomJob = scope.launch {
                animate(
                    initialValue = startScale,
                    targetValue = target,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ) { value, _ ->
                    val k = value / startScale
                    scale.floatValue = value
                    panX.floatValue = clampPan(startPan * k + anchor * (1f - k), value)
                }
            }
        }

        // A rotation changes the viewport, not the intent: keep the zoom and
        // pull the pan back inside the new page edges.
        LaunchedEffect(viewportWidthPx) {
            panX.floatValue = clampPan(panX.floatValue, scale.floatValue)
        }

        // Double-tap toggles between fit-width and detail zoom, spring-animated
        // around the tapped point. One pointer never consumed here, so the list
        // scrolls exactly as it would without the gesture layer.
        val doubleTap = Modifier.pointerInput(viewportWidthPx) {
            detectTapGestures(
                onDoubleTap = { position ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (scale.floatValue > 1f + FIT_EPSILON) {
                        zoomTo(1f)
                    } else {
                        zoomTo(TAP_ZOOM, position.x)
                    }
                },
            )
        }

        // Pinch runs in the Initial pass so it can pre-empt the list's scroll
        // before any drag starts. It only ever consumes while two or more
        // fingers are down (or while finishing a pinch), which is what keeps
        // one-finger scrolling completely native.
        val pinch = Modifier.pointerInput(viewportWidthPx, contentWidthPx) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var zooming = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.count { it.pressed }
                    if (pressed == 0) break
                    if (pressed < 2) {
                        // One finger left after a pinch: keep the rest of this
                        // sequence away from the list, then hand control back.
                        if (zooming) event.changes.forEach { it.consume() }
                        continue
                    }

                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (centroid == Offset.Unspecified) {
                        event.changes.forEach { it.consume() }
                        continue
                    }

                    zoomJob?.cancel()
                    val current = scale.floatValue
                    val next = (current * zoomChange).coerceIn(1f, MAX_ZOOM)
                    val k = next / current
                    val anchor = centroid.x - viewportWidthPx / 2f
                    scale.floatValue = next
                    panX.floatValue = clampPan(
                        panX.floatValue * k + anchor * (1f - k) + panChange.x,
                        next,
                    )
                    // Two fingers moving up must still page down, but through
                    // the list's own physics instead of a second scroll source.
                    if (panChange.y != 0f) listState.dispatchRawDelta(-panChange.y)
                    event.changes.forEach { it.consume() }
                    zooming = true
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(doubleTap)
                    .then(pinch),
                contentPadding = PaddingValues(
                    top = 14.dp,
                    // Leave the last page's number clear of the zoom pill.
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(pageCount, key = { it }) { index ->
                    PdfPage(
                        engine = engine,
                        index = index,
                        total = pageCount,
                        baseWidthPx = contentWidthPx,
                        scale = scale,
                        panX = panX,
                        viewportWidthPx = viewportWidthPx,
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
                canZoomIn = canZoomIn,
                canZoomOut = zoomed,
                onZoomIn = { zoomTo(ZOOM_STOPS.firstOrNull { it > scale.floatValue + FIT_EPSILON } ?: MAX_ZOOM) },
                onZoomOut = { zoomTo(ZOOM_STOPS.lastOrNull { it < scale.floatValue - FIT_EPSILON } ?: 1f) },
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
    baseWidthPx: Float,
    scale: State<Float>,
    panX: State<Float>,
    viewportWidthPx: Float,
    renderScale: Float,
    modifier: Modifier = Modifier,
) {
    val aspect = remember { mutableStateOf<Float?>(null) }
    val bitmap = remember { mutableStateOf<Bitmap?>(null) }
    val renderedWidth = remember { mutableIntStateOf(0) }

    LaunchedEffect(engine, index, baseWidthPx, renderScale) {
        if (baseWidthPx <= 0f) return@LaunchedEffect

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

        val requested = (baseWidthPx * renderScale).roundToInt()
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
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val elevation = if (dark) 1.dp else 2.5.dp
    val shape = RoundedCornerShape(4.dp)

    Column(
        modifier = modifier
            // Width is read in the measure pass, not in composition: a pinch
            // re-lays-out the visible pages without recomposing them, and the
            // GPU stretches the existing bitmap until the zoom settles.
            .layout { measurable, constraints ->
                val width = (baseWidthPx * scale.value).roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(
                    constraints.copy(minWidth = width, maxWidth = width),
                )
                layout(width, placeable.height) { placeable.place(0, 0) }
            }
            // Page at rest sits centred; pan then slides the paper under a
            // fixed viewport, never the viewport across the paper.
            .graphicsLayer {
                val width = baseWidthPx * scale.value
                translationX = (viewportWidthPx - width) / 2f + panX.value
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Paper sits on a surface: a real offset shadow lifts each page in light
        // mode, and in dark mode it reads as ambient occlusion rather than a grey
        // halo, so the page edge stays crisp without fighting the background.
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
