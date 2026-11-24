package com.personal.musicapp

import com.personal.musicapp.data.model.Song
import com.personal.musicapp.data.source.AudioSource
import com.personal.musicapp.data.source.AudioSourceManager
import com.personal.musicapp.data.source.HttpPolicy
import com.personal.musicapp.data.source.SearchBatch
import com.personal.musicapp.data.source.SearchCache
import com.personal.musicapp.data.source.SourceStatus
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class SearchStreamingTest {

    private class FakeSource(
        override val sourceName: String,
        private val behavior: suspend (String) -> List<Song>
    ) : AudioSource {
        var calls = 0
        override suspend fun search(query: String): List<Song> {
            calls++
            return behavior(query)
        }
        override suspend fun parseShareLink(text: String): Song? = null
        override fun canHandleLink(text: String): Boolean = false
    }

    private fun song(id: String, source: String, title: String) =
        Song(id = id, title = title, artist = "周杰伦", audioUrl = "https://example.com/$id.mp3", source = source)

    @Test
    fun testSearchCachePutGetAndEviction() {
        val cache = SearchCache(maxSize = 2, ttlMs = 1000L)
        val dummySong1 = Song(id = "1", title = "Song 1", artist = "Artist 1", coverUrl = "", audioUrl = "", durationMs = 1000)
        val dummySong2 = Song(id = "2", title = "Song 2", artist = "Artist 2", coverUrl = "", audioUrl = "", durationMs = 2000)
        val dummySong3 = Song(id = "3", title = "Song 3", artist = "Artist 3", coverUrl = "", audioUrl = "", durationMs = 3000)

        cache.put("晴天", listOf(dummySong1))
        val hit1 = cache.get("  晴天  ")
        assertNotNull("Cache should hit normalized query", hit1)
        assertEquals(1, hit1!!.size)
        assertEquals("Song 1", hit1[0].title)

        cache.put("七里香", listOf(dummySong2))
        assertEquals(2, cache.size())

        cache.put("稻香", listOf(dummySong3))
        assertEquals(2, cache.size())
        assertNull("Oldest entry '晴天' should have been evicted", cache.get("晴天"))
        assertNotNull("'七里香' should still exist", cache.get("七里香"))
        assertNotNull("'稻香' should exist", cache.get("稻香"))

        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("七里香"))
    }

    /** The real fan-out with fake sources standing in for Kuwo / Netease / YouTube. */
    private fun manager(kuwo: AudioSource, netease: AudioSource, youtube: AudioSource) =
        AudioSourceManager().apply { searchSources = listOf(kuwo, netease, youtube) }

    @Test
    fun settledDoesNotWaitForYouTube() = runBlocking {
        val youtubeGate = CompletableDeferred<Unit>()
        val kuwo = FakeSource(HttpPolicy.KUWO) { listOf(song("kw_1", HttpPolicy.KUWO, "晴天")) }
        val netease = FakeSource(HttpPolicy.NETEASE) { listOf(song("net_1", HttpPolicy.NETEASE, "稻香")) }
        val youtube = FakeSource(HttpPolicy.YOUTUBE) { youtubeGate.await(); listOf(song("yt_1", HttpPolicy.YOUTUBE, "Sunny Day")) }

        val batches = CopyOnWriteArrayList<SearchBatch>()
        val job = launch { manager(kuwo, netease, youtube).searchStream("晴天").collect { batches.add(it) } }
        withTimeout(5_000) { while (batches.none { it.settled }) delay(10) }

        val settled = batches.first { it.settled }
        assertEquals(setOf(HttpPolicy.KUWO, HttpPolicy.NETEASE), settled.outcomes.map { it.source }.toSet())
        assertEquals("YouTube is still pending, so only domestic rows are visible", 2, settled.songs.size)

        youtubeGate.complete(Unit)
        job.join()
        val last = batches.last()
        assertEquals(3, last.outcomes.size)
        assertEquals(setOf("kw_1", "net_1", "yt_1"), last.songs.map { it.id }.toSet())
        assertEquals("yt_1", last.songs.first().id)
        assertTrue(last.settled)
    }

    @Test
    fun oneTimingOutSourceNeitherAbortsTheOthersNorHidesTheCause() = runBlocking {
        val kuwo = FakeSource(HttpPolicy.KUWO) { listOf(song("kw_1", HttpPolicy.KUWO, "晴天")) }
        val netease = FakeSource(HttpPolicy.NETEASE) { throw SocketTimeoutException("slow") }
        val youtube = FakeSource(HttpPolicy.YOUTUBE) { emptyList() }

        val last = manager(kuwo, netease, youtube).searchStream("晴天").last()
        assertEquals(listOf("kw_1"), last.songs.map { it.id })
        val outcome = last.outcomes.single { it.source == HttpPolicy.NETEASE }
        assertEquals(SourceStatus.TIMEOUT, outcome.status)
        assertTrue(outcome.degraded)
        assertEquals(SourceStatus.EMPTY, last.outcomes.single { it.source == HttpPolicy.YOUTUBE }.status)
    }

    @Test
    fun aDegradedSnapshotIsRefetchedInsteadOfServedAsFinal() = runBlocking {
        var neteaseRecovers = false
        val kuwo = FakeSource(HttpPolicy.KUWO) { listOf(song("kw_1", HttpPolicy.KUWO, "晴天")) }
        val netease = FakeSource(HttpPolicy.NETEASE) {
            if (neteaseRecovers) listOf(song("net_1", HttpPolicy.NETEASE, "稻香")) else throw SocketTimeoutException("slow")
        }
        val youtube = FakeSource(HttpPolicy.YOUTUBE) { emptyList() }
        val manager = manager(kuwo, netease, youtube)

        assertEquals(listOf("kw_1"), manager.searchStream("晴天").last().songs.map { it.id })
        assertEquals(1, kuwo.calls)

        neteaseRecovers = true
        assertEquals(setOf("kw_1", "net_1"), manager.searchStream("晴天").last().songs.map { it.id }.toSet())
        assertEquals("A partial snapshot must be refetched on the next search", 2, kuwo.calls)

        val served = manager.searchStream("晴天").toList()
        assertEquals("A complete snapshot is served from cache in one emission", 1, served.size)
        assertTrue(served[0].cached)
        assertTrue(served[0].settled)
        assertEquals(setOf("kw_1", "net_1"), served[0].songs.map { it.id }.toSet())
        assertEquals(2, kuwo.calls)
    }

    @Test
    fun blankQueryIsSettledAndEmpty() = runBlocking {
        val batches = manager(
            FakeSource(HttpPolicy.KUWO) { emptyList() },
            FakeSource(HttpPolicy.NETEASE) { emptyList() },
            FakeSource(HttpPolicy.YOUTUBE) { emptyList() }
        ).searchStream("   ").toList()
        assertEquals(1, batches.size)
        assertTrue(batches[0].songs.isEmpty())
        assertTrue(batches[0].settled)
    }
}
