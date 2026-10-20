package com.secure.reader.features.reader.ui

import android.webkit.WebView
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.secure.reader.core.designsystem.component.ErrorStateBlock
import com.secure.reader.core.designsystem.component.ReaderZoomBar
import com.secure.reader.core.designsystem.component.ShimmerBox
import com.secure.reader.core.designsystem.component.rememberShimmerProgress
import com.secure.reader.features.reader.domain.model.OpenedDocument
import com.secure.reader.features.reader.engine.web.DocumentBridge
import com.secure.reader.features.reader.engine.web.WebAssets
import com.secure.reader.features.reader.engine.web.configureSecure
import kotlin.math.roundToInt
import org.json.JSONObject

private enum class EngineStage { INIT, BUSY, READY, FAILED }

/** WebView zoom bounds and per-tap step for the +/− pill. */
private const val MAX_WEB_ZOOM = 4f
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

            when (stage.value) {
                EngineStage.INIT, EngineStage.BUSY -> EngineLoading(
                    document = document,
                    stageLabel = stageLabel.value,
                )
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
                EngineStage.READY -> ReaderZoomBar(
                    percent = (zoom.value * 100).roundToInt(),
                    canZoomIn = zoom.value < MAX_WEB_ZOOM - FIT_EPSILON,
                    canZoomOut = zoom.value > 1f + FIT_EPSILON,
                    onZoomIn = { webViewRef.value?.zoomByStep(WEB_ZOOM_FACTOR) },
                    onZoomOut = { webViewRef.value?.zoomByStep(1f / WEB_ZOOM_FACTOR) },
                    onReset = { webViewRef.value?.zoomByStep(1f / zoom.value) },
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
 * WebView's zoom is unsupported on some reduced configurations; a rejected
 * request must never take the reader down with it.
 */
private fun WebView.zoomByStep(factor: Float) {
    runCatching { zoomBy(factor) }
}

@Composable
private fun EngineLoading(document: OpenedDocument, stageLabel: String) {
    val shimmer = rememberShimmerProgress()
    val idleLabel = when (document.format) {
        com.secure.reader.core.file.Format.ODF -> "WASM ENGINE · FIRST RUN COMPILES THE MODULE"
        else -> "ENGINE · ${document.format.tag}"
    }
    val label = if (stageLabel == "STARTING ENGINE") idleLabel else stageLabel
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ShimmerBox(
                progress = shimmer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                cornerRadius = 6.dp,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BreathingDot()
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ShimmerBox(progress = shimmer, modifier = Modifier.width(220.dp), height = 12.dp)
            ShimmerBox(progress = shimmer, modifier = Modifier.width(160.dp), height = 12.dp)
        }
    }
}

/**
 * The loading indicator's only real signal that something is alive: a soft
 * scale-and-fade pulse on the status dot. Costs one infinite transition and
 * reads as "working" far better than a bare spinner.
 */
@Composable
private fun BreathingDot(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dot")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(760, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotPulse",
    )
    Box(
        modifier = modifier
            .size(6.dp)
            .graphicsLayer {
                scaleX = pulse
                scaleY = pulse
                alpha = pulse
            }
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
    )
}
