package com.secure.reader.features.reader.engine.web

import android.content.Context
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * Everything the sandboxed engine WebView can reach, under one virtual origin.
 * Local files are served over https://appassets.androidplatform.net/ so the
 * Same-Origin Policy applies exactly as on the web — no file:// anywhere.
 */
object WebAssets {

    const val DOMAIN = "appassets.androidplatform.net"
    const val HARNESS_PATH = "/assets/renderers/office_loader.html"

    fun assetLoader(context: Context): WebViewAssetLoader =
        WebViewAssetLoader.Builder()
            .setDomain(DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler(
                "/internal/",
                WebViewAssetLoader.InternalStoragePathHandler(context, context.filesDir),
            )
            .build()

    fun harnessUrl(): String = "https://$DOMAIN$HARNESS_PATH"

    /** URL of a cached document, reachable only from same-origin harness scripts. */
    fun documentUrl(filesDir: File, cachedFile: File): String {
        val relative = cachedFile.relativeTo(filesDir).path
        return "https://$DOMAIN/internal/$relative"
    }
}
