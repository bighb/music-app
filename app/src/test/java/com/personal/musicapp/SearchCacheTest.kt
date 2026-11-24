package com.personal.musicapp

import com.personal.musicapp.data.model.Song
import com.personal.musicapp.data.source.SearchCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCacheTest {

    private class Clock(var now: Long = 0L) {
        fun time(): Long = now
    }

    private fun songs(n: Int) = (1..n).map {
        Song(id = "kw_$it", title = "晴天$it", artist = "周杰伦", audioUrl = "kuwo://$it", source = "酷我")
    }

    @Test
    fun aDegradedSnapshotExpiresAfterTwentySeconds() {
        val clock = Clock()
        val cache = SearchCache(timeProvider = clock::time)
        cache.put("晴天", songs(1), complete = false)

        clock.now = 15_000L
        val fresh = cache.getHit("晴天")
        assertNotNull("A partial snapshot absorbs a double tap", fresh)
        assertFalse(fresh!!.complete)

        clock.now = 25_000L
        assertNull("A partial snapshot must not be replayed as authoritative", cache.get("晴天"))
    }

    @Test
    fun aCompleteSnapshotLastsTenMinutes() {
        val clock = Clock()
        val cache = SearchCache(timeProvider = clock::time)
        cache.put("晴天", songs(2), complete = true)

        clock.now = 9 * 60_000L
        val hit = cache.getHit("晴天")!!
        assertTrue(hit.complete)
        assertEquals(2, hit.songs.size)

        clock.now = 10 * 60_000L + 1L
        assertNull(cache.getHit("晴天"))
        assertEquals(0, cache.size())
    }

    @Test
    fun degradedIsNeverReadAsComplete() {
        val clock = Clock()
        val cache = SearchCache(timeProvider = clock::time)
        cache.put("晴天", songs(1), complete = false)
        assertFalse(cache.getHit("晴天")!!.complete)

        cache.put("晴天", songs(3), complete = true)
        clock.now = 19_000L
        assertTrue("A later complete snapshot replaces the partial one", cache.getHit("晴天")!!.complete)

        cache.put("晴天", songs(1), complete = false)
        assertFalse(cache.getHit("晴天")!!.complete)
    }

    @Test
    fun removeAndBlankQueries() {
        val cache = SearchCache(timeProvider = Clock()::time)
        cache.put("晴天", songs(1))
        cache.remove("  晴天 ")
        assertNull(cache.get("晴天"))

        cache.put("   ", songs(1))
        cache.put("雨", emptyList())
        assertEquals(0, cache.size())
    }

    @Test
    fun putGetAndEviction() {
        val cache = SearchCache(maxSize = 2, ttlMs = 1000L, timeProvider = Clock()::time)
        cache.put("晴天", songs(1))
        assertEquals("kw_1", cache.get("  晴天  ")!![0].id)
        cache.put("七里香", songs(2))
        assertEquals(2, cache.size())
        cache.put("稻香", songs(3))
        assertEquals(2, cache.size())
        assertNull("Oldest entry is evicted", cache.get("晴天"))
        assertNotNull(cache.get("七里香"))
        assertNotNull(cache.get("稻香"))
        cache.clear()
        assertEquals(0, cache.size())
    }
}
