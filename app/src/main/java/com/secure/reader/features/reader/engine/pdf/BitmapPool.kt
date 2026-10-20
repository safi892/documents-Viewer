package com.secure.reader.features.reader.engine.pdf

import android.graphics.Bitmap

/**
 * Keeps a few viewport-sized bitmaps alive so scroll-back does not re-allocate.
 *
 * Only same-dimensions bitmaps are reused; anything else the caller rendered is
 * recycled instead of pooled.
 *
 * The free list is capped in *bytes*, not in bitmaps. Page rasters change size
 * with the zoom step, so a count-based cap of three could silently pin three
 * 16 MB buffers — 48 MB of memory doing nothing — while a byte budget bounds
 * the pool at whatever the current page size actually is.
 */
class BitmapPool(private val maxBytes: Int = DEFAULT_MAX_BYTES) {

    private val free = ArrayDeque<Bitmap>()
    private var freeBytes = 0
    private val lock = Any()

    fun acquire(width: Int, height: Int): Bitmap? = synchronized(lock) {
        val match = free.firstOrNull { it.width == width && it.height == height && !it.isRecycled }
        if (match != null) {
            free.remove(match)
            freeBytes -= match.allocationByteCount
            match
        } else {
            null
        }
    }

    fun release(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        synchronized(lock) {
            val bytes = bitmap.allocationByteCount
            // Evict oldest-first until this buffer fits; the least-recently
            // returned buffer is the least likely to be asked for again.
            while (free.isNotEmpty() && freeBytes + bytes > maxBytes) {
                val evicted = free.removeFirst()
                freeBytes -= evicted.allocationByteCount
                evicted.recycle()
            }
            if (freeBytes + bytes <= maxBytes) {
                free.addLast(bitmap)
                freeBytes += bytes
            } else {
                bitmap.recycle()
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            free.forEach { it.recycle() }
            free.clear()
            freeBytes = 0
        }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 24 * 1024 * 1024
    }
}
