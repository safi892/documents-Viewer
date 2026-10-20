package com.secure.reader.features.reader.engine.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat

/**
 * Page scales the reader recognises, where 1.0 is exactly fit-to-width.
 *
 * 100% has to mean "the paper fills the screen", not "the engine's initial
 * scale": WebView's overview mode can start a page below 1.0, and a pill that
 * labels that state 100% then lets the content shrink into a corner of an
 * empty screen. These bounds are enforced in [configureSecure] for pinch and
 * reused by the +/− controls so both paths agree.
 */
internal const val WEB_MIN_SCALE = 1f
internal const val WEB_MAX_SCALE = 4f

/**
 * JS -> Kotlin lifecycle channel for the office harness. WebView invokes these
 * on a binder thread; everything is re-posted to the main thread before it
 * touches Compose state.
 */
class DocumentBridge {

    private val main = Handler(Looper.getMainLooper())

    var onReady: (() -> Unit)? = null
    var onStage: ((String) -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Harness scripts are loaded and window.__loadDocument exists. */
    @JavascriptInterface
    fun ready() {
        main.post { onReady?.invoke() }
    }

    /** "reading" | "rendering" — drives the loading overlay copy. */
    @JavascriptInterface
    fun stage(name: String) {
        main.post { onStage?.invoke(name) }
    }

    @JavascriptInterface
    fun done() {
        main.post { onDone?.invoke() }
    }

    @JavascriptInterface
    fun error(message: String) {
        main.post { onError?.invoke(message) }
    }
}

/**
 * The render engines can only be diagnosed from the outside through their
 * console, which a WebView otherwise swallows. Every message the harness logs
 * is forwarded to logcat under one tag.
 */
class EngineLogClient : WebChromeClient() {
    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        Log.d(TAG, "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
        return true
    }

    private companion object {
        const val TAG = "ReaderEngine"
    }
}

/**
 * The zero-trust WebView configuration from the plan: JS on (the engines need
 * it), every local filesystem route off, local content only through the
 * WebViewAssetLoader virtual domain.
 */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.configureSecure(
    context: Context,
    bridge: DocumentBridge,
    /** Reports the live page scale so the zoom pill can mirror pinch and buttons. */
    onScale: (Float) -> Unit = {},
): WebView {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        // Pinch zoom + fling scroll, no visible +/- controls. Overview mode is
        // deliberately off: it lets the engine pick a fit scale below 1.0, and
        // then "100%" no longer means "fills the screen" — zooming could make
        // the page narrower than the viewport with no way back. With it off,
        // the harness meta viewport (width=device-width, initial-scale=1) lays
        // the sheet out at the view width, so 1.0 is exactly fit-to-width.
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        setUseWideViewPort(true)
        setLoadWithOverviewMode(false)
        setInitialScale(100)
        textZoom = 100
    }
    // The documents deliberately keep their own (light) paper colour in every
    // theme, so let the WebView algorithmically darken them and it would invert
    // the paper while leaving the ink dark — a page of dark text on dark paper.
    // The harness has its own dark treatment for the gutter around the pages.
    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
    webChromeClient = EngineLogClient()
    val loader = WebAssets.assetLoader(context)
    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)?.noStore()

        override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
            if (newScale <= 0f) return
            // The engine can still pinch past the reader's bounds (and a stale
            // overview scale can arrive while the document is settling). Report
            // the clamped value and push the page back onto it, so the pill can
            // never disagree with what is on screen.
            val clamped = newScale.coerceIn(WEB_MIN_SCALE, WEB_MAX_SCALE)
            if (clamped != newScale) {
                val factor = clamped / newScale
                view.post { runCatching { view.zoomBy(factor) } }
            }
            onScale(clamped)
        }
    }
    addJavascriptInterface(bridge, "AndroidBridge")
    return this
}

/**
 * Everything served here already lives on the device, so WebView caching buys
 * nothing and costs correctness: a stale harness or engine bundle would keep
 * running after an app update. Tag every intercepted response no-store.
 */
private fun WebResourceResponse.noStore(): WebResourceResponse {
    val headers = HashMap<String, String>(responseHeaders ?: emptyMap())
    headers["Cache-Control"] = "no-store"
    setResponseHeaders(headers)
    return this
}
