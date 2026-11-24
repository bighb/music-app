package com.personal.musicapp

import com.personal.musicapp.data.model.Song
import com.personal.musicapp.data.source.HttpPolicy
import com.personal.musicapp.data.source.SearchMerger
import com.personal.musicapp.data.source.SearchMerger.Companion.trackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls the production merger that the fan-out uses; nothing here re-implements merge logic. */
class SearchMergerTest {

    private val kw = HttpPolicy.KUWO
    private val net = HttpPolicy.NETEASE
    private val yt = HttpPolicy.YOUTUBE

    private fun song(id: String, title: String, artist: String, source: String) =
        Song(id = id, title = title, artist = artist, audioUrl = "https://example.com/$id.mp3", source = source)

    private fun ids(merger: SearchMerger) = merger.snapshot().map { it.id }

    @Test
    fun domesticRowsArrivingFirstKeepTheirArrivalOrder() {
        val merger = SearchMerger()
        assertTrue(merger.add(listOf(song("kw_1", "晴天", "周杰伦", kw))))
        assertTrue(merger.add(listOf(song("net_1", "稻香", "周杰伦", net), song("net_2", "七里香", "周杰伦", net))))
        assertEquals(listOf("kw_1", "net_1", "net_2"), ids(merger))
    }

    @Test
    fun youtubeStaysInFrontWheneverItArrives() {
        val late = SearchMerger()
        late.add(listOf(song("kw_1", "晴天", "周杰伦", kw), song("net_1", "稻香", "周杰伦", net)))
        assertTrue(late.add(listOf(song("yt_1", "Sunny Day", "Jay Chou", yt))))
        assertEquals(listOf("yt_1", "kw_1", "net_1"), ids(late))

        val early = SearchMerger()
        early.add(listOf(song("yt_1", "Sunny Day", "Jay Chou", yt)))
        early.add(listOf(song("kw_1", "晴天", "周杰伦", kw)))
        assertEquals(listOf("yt_1", "kw_1"), ids(early))
    }

    @Test
    fun sameIdIsKeptOnceAcrossBatches() {
        val merger = SearchMerger()
        val row = song("kw_1", "晴天", "周杰伦", kw)
        assertTrue(merger.add(listOf(row)))
        assertFalse("A repeated id must not change the visible list", merger.add(listOf(row)))
        assertEquals(listOf("kw_1"), ids(merger))
    }

    @Test
    fun exactTitleAndArtistMergePrefersTheDomesticRow() {
        val merger = SearchMerger()
        val fanUpload = song("yt_1", "晴天", "周杰伦", yt)
        val studio = song("kw_1", "晴天", "周杰伦", kw)
        merger.add(listOf(fanUpload))
        assertTrue(merger.add(listOf(studio)))

        assertEquals(listOf("kw_1"), ids(merger))
        assertEquals(1, merger.mergedDrops)
        assertEquals(listOf(fanUpload), merger.alternativesFor(studio))
    }

    @Test
    fun domesticRankIsKuwoOverNeteaseOverYoutubeWhateverTheArrivalOrder() {
        val merger = SearchMerger()
        merger.add(listOf(song("yt_1", "晴天", "周杰伦", yt)))
        merger.add(listOf(song("net_1", "晴天", "周杰伦", net)))
        assertEquals(listOf("net_1"), ids(merger))
        merger.add(listOf(song("kw_1", "晴天", "周杰伦", kw)))
        assertEquals(listOf("kw_1"), ids(merger))
        assertEquals(2, merger.mergedDrops)
        assertEquals(2, merger.alternativesFor(merger.snapshot()[0]).size)
    }

    @Test
    fun aDroppedYoutubeRowStaysReachableForCrossSourceFallback() {
        val merger = SearchMerger()
        val studio = song("net_1", "晴天", "周杰伦", net)
        val live = song("yt_1", "晴天", "周杰伦", yt)
        merger.add(listOf(studio))
        merger.add(listOf(live))
        assertEquals(listOf("net_1"), ids(merger))
        assertEquals(listOf(live), merger.alternativesFor(studio))
    }

    @Test
    fun nearMissesAreNotMerged() {
        val merger = SearchMerger()
        merger.add(listOf(song("kw_1", "晴天", "周杰伦", kw)))
        merger.add(
            listOf(
                song("yt_1", "晴天 (Live)", "周杰伦", yt),
                song("yt_2", "晴天", "周杰伦翻唱", yt),
                song("yt_3", "", "周杰伦", yt),
                song("yt_4", "晴天", "", yt)
            )
        )
        assertEquals(listOf("yt_1", "yt_2", "yt_3", "yt_4", "kw_1"), ids(merger))
        assertEquals(0, merger.mergedDrops)
    }

    @Test
    fun trackKeyIsCaseAndWhitespaceInsensitiveButNeverPartial() {
        assertEquals(trackKey(song("a", " Sunny Day ", "JAY CHOU", yt)), trackKey(song("b", "sunny day", "Jay Chou", kw)))
        assertNull(trackKey(song("c", " ", "周杰伦", kw)))
        assertNull(trackKey(song("d", "晴天", " ", kw)))
    }

    @Test
    fun rowsWithoutATrackKeyAreStillListed() {
        val merger = SearchMerger()
        assertTrue(merger.add(listOf(song("yt_3", "", "周杰伦", yt))))
        assertEquals(listOf("yt_3"), ids(merger))
    }

    @Test
    fun resetDropsEverythingFromThePreviousQuery() {
        val merger = SearchMerger()
        merger.add(listOf(song("kw_1", "晴天", "周杰伦", kw)))
        merger.reset()
        assertEquals(0, merger.snapshot().size)
        assertEquals(0, merger.trackKeyCount())
        assertEquals(0, merger.mergedDrops)
        assertTrue(merger.add(listOf(song("kw_1", "晴天", "周杰伦", kw))))
    }

    @Test
    fun crossIndexIsBounded() {
        val merger = SearchMerger(maxTrackKeys = 4)
        val rows = (1..8).map { song("kw_$it", "歌$it", "歌手", kw) }
        merger.add(rows)
        assertEquals(8, merger.snapshot().size)
        assertEquals(4, merger.trackKeyCount())
        assertTrue(merger.alternativesFor(rows[0]).isEmpty())
    }
}
