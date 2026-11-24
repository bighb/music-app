package com.personal.musicapp

import com.personal.musicapp.data.source.AudioFileVerifier
import com.personal.musicapp.data.source.PlaceholderGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaceholderGateTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun knownPromoUrlIsRejected() {
        assertTrue(PlaceholderGate.isPromo("http://x.kuwo.cn/star/588957081.mp3"))
        assertFalse(PlaceholderGate.isPromo("http://x.kuwo.cn/star/588957082.mp3"))
    }

    @Test
    fun aStreamFarShorterThanTheCatalogueEntryIsAPlaceholder() {
        assertTrue(PlaceholderGate.isDurationMismatch(expectedMs = 240_000L, actualMs = 11_000L))
        assertTrue(PlaceholderGate.isDurationMismatch(expectedMs = 240_000L, actualMs = 119_999L))
    }

    @Test
    fun theRealSongIsAccepted() {
        assertFalse(PlaceholderGate.isDurationMismatch(expectedMs = 240_000L, actualMs = 238_000L))
        assertFalse(PlaceholderGate.isDurationMismatch(expectedMs = 240_000L, actualMs = 600_000L))
    }

    @Test
    fun withoutAReliableDurationTheGateFailsOpen() {
        assertFalse(PlaceholderGate.isDurationMismatch(expectedMs = 0L, actualMs = 11_000L))
        assertFalse(PlaceholderGate.isDurationMismatch(expectedMs = 240_000L, actualMs = 0L))
        assertFalse(PlaceholderGate.isDurationMismatch(expectedMs = 240_000L, actualMs = -1L))
        assertFalse(PlaceholderGate.isDurationMismatch(expectedMs = 20_000L, actualMs = 1_000L))
    }

    @Test
    fun sizeFloorIsDerivedFromTheCatalogueDuration() {
        assertEquals(10_000L, AudioFileVerifier.minBytes(0L))
        assertEquals(10_000L, AudioFileVerifier.minBytes(-5L))
        assertEquals(24_000L / 8 * 240, AudioFileVerifier.minBytes(240_000L))
    }

    @Test
    fun theOldTenKilobyteGateWouldHaveAcceptedThePromoClip() {
        // 11 s at 128 kbps: far above the old 10 000-byte floor, far below the real song.
        val promoBytes = 11L * 128_000L / 8
        assertTrue(promoBytes > 10_000L)
        assertTrue(promoBytes < AudioFileVerifier.minBytes(240_000L))
    }

    @Test
    fun isPlausibleChecksExistenceAndSize() {
        val promo = folder.newFile("promo.mp3").apply { writeBytes(ByteArray(176_000)) }
        assertFalse(AudioFileVerifier.isPlausible(promo, 240_000L))
        assertTrue(AudioFileVerifier.isPlausible(promo, 0L))
        assertFalse(AudioFileVerifier.isPlausible(folder.getRoot().resolve("missing.mp3"), 240_000L))
    }
}
