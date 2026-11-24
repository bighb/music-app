package com.personal.musicapp.data.source

import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Detects anti-piracy / preview placeholders that are served with HTTP 200. A registry of
 * known URLs catches what we have seen; duration reconciliation catches what we have not.
 */
object PlaceholderGate {

    private val promoUrlPatterns = listOf("588957081.mp3")

    private const val MIN_EXPECTED_MS = 30_000L
    private const val MIN_RATIO = 0.5

    fun isPromo(url: String): Boolean = promoUrlPatterns.any { url.contains(it) }

    /**
     * True when the real stream is far SHORTER than the catalogue duration. Only shorter
     * counts: previews and promo clips are short, while a longer stream is simply another
     * version of the song and must not be discarded.
     */
    fun isDurationMismatch(expectedMs: Long, actualMs: Long): Boolean {
        if (expectedMs < MIN_EXPECTED_MS || actualMs <= 0L) return false
        return actualMs < expectedMs * MIN_RATIO
    }
}

/** Local-file plausibility: size floor from the catalogue duration, then real duration. */
object AudioFileVerifier {

    private const val MIN_BYTES = 10_000L
    private const val MIN_BITRATE_BPS = 24_000L

    fun minBytes(durationMs: Long): Long {
        if (durationMs <= 0L) return MIN_BYTES
        return maxOf(MIN_BYTES, MIN_BITRATE_BPS / 8 * durationMs / 1000)
    }

    fun isPlausible(file: File, durationMs: Long): Boolean {
        return file.isFile && file.length() >= minBytes(durationMs)
    }

    /** Fail-open: a file whose duration cannot be read is not rejected here. */
    fun hasPlausibleDuration(file: File, durationMs: Long): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val actual = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            actual == null || !PlaceholderGate.isDurationMismatch(durationMs, actual)
        } catch (_: Exception) {
            true
        } finally {
            retriever.release()
        }
    }
}
