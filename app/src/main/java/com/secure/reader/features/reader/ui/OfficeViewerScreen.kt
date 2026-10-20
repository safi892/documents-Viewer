package com.secure.reader.features.reader.ui

import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.secure.reader.core.designsystem.component.ErrorStateBlock
import com.secure.reader.core.designsystem.component.Motion
import com.secure.reader.core.designsystem.component.PageSkeleton
import com.secure.reader.core.designsystem.component.ReaderZoomBar
import com.secure.reader.features.reader.domain.model.OpenedDocument
import com.secure.reader.features.reader.engine.web.DocumentBridge
import com.secure.reader.features.reader.engine.web.WEB_MAX_SCALE
import com.secure.reader.features.reader.engine.web.WEB_MIN_SCALE
import com.secure.reader.features.reader.engine.web.WebAssets
import com.secure.reader.features.reader.engine.web.configureSecure
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONObject

private enum class EngineStage { INIT, BUSY, READY, FAILED }

/** Per-tap step for the +/− pill; bounds live with the WebView configuration. */
private const val WEB_ZOOM_FACTOR = 1.25f
private const val FIT_EPSILON = 0.01f

/**
 * Sandboxed office renderer: the bundled JS engines run inside a WebView that
 * only ever talks to the WebViewAssetLoader virtual origin. The cached file is
 * fetched same-origin by the harness — no file:// exposure anywhere.
 *
 * The WebView owns vertical scrolling and pinch; the overlay pill zips that
 * same page scale so the +/− steps stay in lock-step with the fingers.
 */
@Composable
fun OfficeViewerScreen(
    document: OpenedDocument,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val filesDir = remember(context) { context.filesDir }
    val retryToken = remember { mutableStateOf(0) }

    key(retryToken.value) {
        val stage = remember { mutableStateOf(EngineStage.INIT) }
        val stageLabel = remember { mutableStateOf("STARTING ENGINE") }
        val errorMessage = remember { mutableStateOf<String?>(null) }
        val webViewRef = remember { mutableStateOf<WebView?>(null) }
        val zoom = remember { mutableFloatStateOf(1f) }

        val bridge = remember(document.id) {
            DocumentBridge().apply {
                onReady = {
                    val fileUrl = WebAssets.documentUrl(filesDir, document.cachedFile)
                    webViewRef.value?.evaluateJavascript(
                        "window.__loadDocument(" +
                            "${JSONObject.quote(fileUrl)}, " +
                            "${JSONObject.quote(document.format.harnessKey)}, " +
                            "${JSONObject.quote(document.fileName)})", null,
                    )
                }
                onStage = { name ->
                    stage.value = EngineStage.BUSY
                    stageLabel.value = when (name) {
                        "reading" -> "READING FILE"
                        "rendering" -> "RENDERING WITH ${document.format.tag} ENGINE"
                        "laying-out" -> "LAYING OUT"
                        else -> "WORKING"
                    }
                }
                onDone = { stage.value = EngineStage.READY }
                onError = { message ->
                    errorMessage.value = message
                    stage.value = EngineStage.FAILED
                }
            }
        }

        Box(modifier.fillMaxSize()) {
            AndroidView(
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        configureSecure(viewContext, bridge) { newScale -> zoom.value = newScale }
                        webViewRef.value = this
                        loadUrl(WebAssets.harnessUrl())
                    }
                },
                onRelease = { view ->
                    webViewRef.value = null
                    view.destroy()
                },
                modifier = Modifier.fillMaxSize(),
            )

            // The skeleton is a veil over a page that is already painting. Cross-
            // fading it out (instead of removing it) is the difference between the
            // document "appearing" and the document "settling" — and it costs one
            // layer for one routine duration.
            val loading = stage.value == EngineStage.INIT || stage.value == EngineStage.BUSY
            AnimatedVisibility(
                visible = loading || stage.value == EngineStage.FAILED,
                enter = EnterTransition.None,
                exit = fadeOut(tween(Motion.ROUTINE_MS, easing = Motion.EaseOut)),
            ) {
                when (stage.value) {
                    EngineStage.FAILED -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(20.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        ErrorStateBlock(
                            title = "Rendering failed",
                            body = "The ${document.format.tag} engine could not render this file." +
                                (errorMessage.value?.let { "\n\n$it" } ?: ""),
                        )
                    }
                    else -> EngineLoading(
                        document = document,
                        stageLabel = stageLabel.value,
                    )
                }
            }

            if (stage.value == EngineStage.READY) {
                WebZoomBar(
                    zoom = zoom,
                    webView = { webViewRef.value },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 18.dp),
                )
            }
        }
    }
}

/**
 * The zoom pill reads the live page scale through `derivedStateOf`, and only
 * this composable subscribes. The scale is written on every pinch frame by
 * `onScaleChanged`; reading it directly where the WebView lives would recompose
 * the reader -- AndroidView included -- at gesture rate.
 */
@Composable
private fun WebZoomBar(
    zoom: State<Float>,
    webView: () -> WebView?,
    modifier: Modifier = Modifier,
) {
    val percent by remember { derivedStateOf { (zoom.value * 100).roundToInt() } }
    val canZoomIn by remember { derivedStateOf { zoom.value < WEB_MAX_SCALE - FIT_EPSILON } }
    val canZoomOut by remember { derivedStateOf { zoom.value > 1f + FIT_EPSILON } }

    ReaderZoomBar(
        percent = percent,
        canZoomIn = canZoomIn,
        canZoomOut = canZoomOut,
        onZoomIn = { webView()?.zoomTo(zoom.value, zoom.value * WEB_ZOOM_FACTOR) },
        onZoomOut = { webView()?.zoomTo(zoom.value, zoom.value / WEB_ZOOM_FACTOR) },
        // Reset reads the live scale at the moment of the tap, not a snapshot.
        onReset = { webView()?.zoomTo(zoom.value, WEB_MIN_SCALE) },
        modifier = modifier,
    )
}

/**
 * Steps to [target] from the live [current] scale, so a tapped stop lands
 * exactly on the percentage the pill reports next. The engine only accepts a
 * relative factor, and the same bounds are enforced on pinch in
 * `onScaleChanged`; this is one gesture path, not a second source of truth.
 *
 * WebView's zoom is unsupported on some reduced configurations; a rejected
 * request must never take the reader down with it.
 */
private fun WebView.zoomTo(current: Float, target: Float) {
    if (current <= 0f) return
    val clamped = target.coerceIn(WEB_MIN_SCALE, WEB_MAX_SCALE)
    if (abs(clamped - current) < 0.001f) return
    runCatching { zoomBy(clamped / current) }
}

@Composable
private fun EngineLoading(document: OpenedDocument, stageLabel: String) {
    val idleLabel = when (document.format) {
        com.secure.reader.core.file.Format.ODF -> "WASM ENGINE · FIRST RUN COMPILES THE MODULE"
        else -> "ENGINE · ${document.format.tag}"
    }
    val label = if (stageLabel == "STARTING ENGINE") idleLabel else stageLabel
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        PageSkeleton(
            modifier = Modifier.fillMaxSize(),
            pageHeight = 300.dp,
            label = label,
        )
    }
}
