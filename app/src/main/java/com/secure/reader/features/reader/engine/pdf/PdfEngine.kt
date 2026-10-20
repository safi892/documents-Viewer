package com.secure.reader.features.reader.engine.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Thin wrapper over the native [PdfRenderer].
 *
 * Render targets are viewport-sized (never full page-inches), all renderer
 * access is serialized ([PdfRenderer] is not thread-safe), and bitmaps come
 * from / go back to a small [BitmapPool] so repeated paging reuses memory.
 *
 * Rasterisation is a suspend function: it runs on a bounded dispatcher so a
 * pinch never competes with the frame pipeline, and it is cancellation-aware —
 * if the page scrolls out mid-render the pooled bitmap is handed straight back
 * instead of leaking into the GC.
 *
 * One page is one bitmap, so a raster is bounded by [MAX_RASTER_PIXELS]: a
 * full-page bitmap at a deep zoom would otherwise be tens of megabytes.
 */
class PdfEngine private constructor(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
    private val pool: BitmapPool,
) : AutoCloseable {

    private val lock = Any()

    /**
     * Page shapes, keyed by index. The viewer asks for these every time a page
     * scrolls back into composition, and a lazy list discards its own copy when
     * it does, so without this cache each return visit reopens the page.
     */
    private val aspects = HashMap<Int, Float>()

    /**
     * At most two raster jobs in flight: enough to keep the visible viewport
     * sharp on entry, and never enough to starve the UI threads of a low-core
     * device. The renderer itself is serialized by [lock] regardless.
     */
    private val raster: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

    val pageCount: Int
        get() = synchronized(lock) { renderer.pageCount }

    /** width / height in page points, read once per page and cached. */
    suspend fun aspectRatio(index: Int): Float = withContext(raster) {
        coroutineContext.ensureActive()
        synchronized(lock) {
            aspects[index]?.let { return@synchronized it }
            val page = try {
                renderer.openPage(index)
            } catch (_: IllegalStateException) {
                // Renderer was closed before this page could open.
                return@synchronized DEFAULT_ASPECT
            }
            try {
                val width = page.width.toFloat()
                val height = page.height.toFloat()
                (if (height <= 0f) DEFAULT_ASPECT else width / height)
                    .also { aspects[index] = it }
            } finally {
                page.close()
            }
        }
    }

    /**
     * Renders [index] into a pooled bitmap sized [targetWidthPx] wide, scaled
     * to the page aspect. The caller must return it via [release].
     */
    suspend fun render(index: Int, targetWidthPx: Int): Bitmap? = withContext(raster) {
        coroutineContext.ensureActive()
        if (targetWidthPx <= 0) return@withContext null
        synchronized(lock) {
            if (index < 0 || index >= renderer.pageCount) return@synchronized null
            val page = try {
                renderer.openPage(index)
            } catch (_: IllegalStateException) {
                return@synchronized null
            }
            try {
                val pageWidth = page.width.toFloat()
                val pageHeight = page.height.toFloat()
                if (pageWidth <= 0f || pageHeight <= 0f) return@synchronized null

                val aspect = pageWidth / pageHeight
                val width = rasterWidth(targetWidthPx, aspect)
                val height = max(1, (width * pageHeight / pageWidth).roundToInt())
                val bitmap = pool.acquire(width, height)
                    ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    val matrix = Matrix().apply {
                        setScale(width / pageWidth, height / pageHeight)
                    }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } catch (error: Throwable) {
                    // Raster failed: never leak the pooled buffer.
                    pool.release(bitmap)
                    throw error
                }
                // Rasterised — but if the caller was cancelled while we worked
                // (page scrolled away), hand the bitmap back and stop here.
                if (!coroutineContext.isActive) {
                    pool.release(bitmap)
                    coroutineContext.ensureActive()
                }
                bitmap
            } finally {
                page.close()
            }
        }
    }

    fun release(bitmap: Bitmap) = pool.release(bitmap)

    override fun close() {
        synchronized(lock) { renderer.close() }
        runCatching { descriptor.close() }
        pool.clear()
    }

    companion object {
        private const val DEFAULT_ASPECT = 1f / 1.414f

        /**
         * Ceiling on a single page bitmap, in pixels — about 16 MB at four bytes
         * a pixel.
         *
         * Raster cost grows with the *square* of the zoom: a 1080 px viewport at
         * 3x asks for 3240x4581, which is 59 MB for one page, and with a couple
         * of pages composed that is an out-of-memory crash rather than a slow
         * frame. Past this ceiling the GPU scales the bitmap up instead: softer
         * at extreme zoom, but stable, and ungraspable memory is the worse
         * trade. Rendering only the visible *region* of a page is what would
         * lift the ceiling properly, and is a larger change than this one.
         */
        private const val MAX_RASTER_PIXELS = 4_000_000

        /**
         * Clamps a requested raster width for a page of [aspect] (width/height).
         * Pure, so the viewer can predict the size it will get and skip a
         * re-render that the ceiling has already collapsed onto the same size.
         */
        fun rasterWidth(requestedWidthPx: Int, aspect: Float): Int {
            // w * (w / aspect) <= MAX_RASTER_PIXELS  =>  w <= sqrt(max * aspect)
            val safeAspect = aspect.coerceIn(0.05f, 20f)
            val ceiling = sqrt(MAX_RASTER_PIXELS.toDouble() * safeAspect).toInt()
            return requestedWidthPx.coerceAtMost(ceiling).coerceAtLeast(1)
        }

        @Throws(IOException::class)
        fun open(file: File, pool: BitmapPool = BitmapPool()): PdfEngine {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                PdfEngine(PdfRenderer(descriptor), descriptor, pool)
            } catch (error: Throwable) {
                runCatching { descriptor.close() }
                throw error
            }
        }
    }
}
