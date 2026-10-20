package com.secure.reader.features.reader.engine.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Outcome of a decode attempt: the pixels, or a reason a human can read. */
sealed interface ImageDecode {
    data class Success(val bitmap: Bitmap) : ImageDecode
    data class Failure(val reason: String) : ImageDecode
}

/**
 * Bounded, cancellation-aware raster decoding for the image viewer.
 *
 * Two rules keep a 108 MP phone photo from taking the app down:
 *  1. never decode at native size — a power-of-two [BitmapFactory.Options.inSampleSize]
 *     brings the pixels down to roughly the target first;
 *  2. never exceed [MAX_DECODED_PIXELS], whatever the source claims.
 *
 * Decoding is blocking inside the platform codec and cannot be interrupted, so
 * it runs on a bounded IO lane and the result is recycled if the caller was
 * cancelled while it ran — switching documents mid-decode must not leave a
 * full-resolution bitmap stranded in the heap.
 */
object ImageRenderer {

    /** ~12 MP at ARGB_8888 is ≈48 MB; beyond that a low-RAM device starts thrashing. */
    private const val MAX_DECODED_PIXELS = 12_000_000L
    private const val MAX_SAMPLE_SIZE = 64

    private val decodeLane: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    /**
     * Decodes [file] so that its width is at least [targetWidthPx] (bitmap
     * decoding only halves, so the result lands between the target and 2×).
     * Returns [ImageDecode.Failure] rather than throwing: an undecodable file is
     * an expected outcome, not a bug.
     */
    suspend fun decode(file: File, targetWidthPx: Int): ImageDecode = withContext(decodeLane) {
        currentCoroutineContext().ensureActive()
        if (targetWidthPx <= 0) return@withContext ImageDecode.Failure("No space to draw the image.")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return@withContext ImageDecode.Failure(UNSUPPORTED)
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(sourceWidth, sourceHeight, targetWidthPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: return@withContext ImageDecode.Failure(UNSUPPORTED)

        // The user moved on while the codec was working: give the memory back.
        if (!currentCoroutineContext().isActive) {
            bitmap.recycle()
            currentCoroutineContext().ensureActive()
        }
        ImageDecode.Success(bitmap)
    }

    /**
     * Largest power of two that still leaves the image at least [targetWidthPx]
     * wide, then raised further if the result would still bust the pixel budget.
     */
    private fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, targetWidthPx: Int): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidthPx && sample < MAX_SAMPLE_SIZE) {
            sample *= 2
        }
        while (sample < MAX_SAMPLE_SIZE && exceedsBudget(sourceWidth, sourceHeight, sample)) {
            sample *= 2
        }
        return sample
    }

    private fun exceedsBudget(sourceWidth: Int, sourceHeight: Int, sample: Int): Boolean {
        val width = sourceWidth / sample
        val height = sourceHeight / sample
        return width.toLong() * height.toLong() > MAX_DECODED_PIXELS
    }

    private const val UNSUPPORTED = "This image uses a codec this device cannot decode."
}
