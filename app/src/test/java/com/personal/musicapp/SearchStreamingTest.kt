package com.personal.musicapp

import com.personal.musicapp.data.model.Song
import com.personal.musicapp.data.source.AudioSourceManager
import com.personal.musicapp.data.source.SearchCache
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchStreamingTest {

    @Test
    fun testSearchCachePutGetAndEviction() {
        val cache = SearchCache(maxSize = 2, ttlMs = 1000L)
        val dummySong1 = Song(id = "1", title = "Song 1", artist = "Artist 1", coverUrl = "", audioUrl = "", durationMs = 1000)
        val dummySong2 = Song(id = "2", title = "Song 2", artist = "Artist 2", coverUrl = "", audioUrl = "", durationMs = 2000)
        val dummySong3 = Song(id = "3", title = "Song 3", artist = "Artist 3", coverUrl = "", audioUrl = "", durationMs = 3000)

        // Put query 1
        cache.put("晴天", listOf(dummySong1))
        // Verify case and whitespace insensitivity
        val hit1 = cache.get("  晴天  ")
        assertNotNull("Cache should hit normalized query", hit1)
        assertEquals(1, hit1!!.size)
        assertEquals("Song 1", hit1[0].title)

        // Put query 2
        cache.put("七里香", listOf(dummySong2))
        assertEquals(2, cache.size())

        // Put query 3 (should evict query 1 since maxSize is 2 and query 2 was added)
        cache.put("稻香", listOf(dummySong3))
        assertEquals(2, cache.size())
        assertNull("Oldest entry '晴天' should have been evicted", cache.get("晴天"))
        assertNotNull("'七里香' should still exist", cache.get("七里香"))
        assertNotNull("'稻香' should exist", cache.get("稻香"))

        // Test clear
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("七里香"))
    }

    @Test
    fun testSearchStreamEmitsProgressivelyAndCaches() = runBlocking {
        val manager = AudioSourceManager()
        val query = "晴天"

        val startTime = System.currentTimeMillis()
        val emissions = mutableListOf<List<Song>>()

        manager.searchStream(query).collect { batch ->
            emissions.add(batch)
            println("Stream emitted ${batch.size} songs at ${System.currentTimeMillis() - startTime}ms")
        }

        assertTrue("Should emit at least once", emissions.isNotEmpty())
        val finalResult = emissions.last()
        assertTrue("Final emission should have results", finalResult.isNotEmpty())

        // Verify cache hit on second search
        val cacheStartTime = System.currentTimeMillis()
        val cachedResults = manager.search(query)
        val cacheDuration = System.currentTimeMillis() - cacheStartTime

        println("Cache hit duration: ${cacheDuration}ms, items: ${cachedResults.size}")
        assertEquals("Cached count should match final emission", finalResult.size, cachedResults.size)
        assertTrue("Cache hit should take less than 20ms", cacheDuration < 20)
    }
}
