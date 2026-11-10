package com.personal.musicapp.data.source

import com.personal.musicapp.data.model.Song
import java.util.LinkedHashMap

class SearchCache(
    private val maxSize: Int = 50,
    private val ttlMs: Long = 10 * 60 * 1000L // 10 minutes TTL
) {
    private data class CacheEntry(
        val songs: List<Song>,
        val timestamp: Long
    )

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, CacheEntry>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxSize
        }
    }

    fun get(query: String): List<Song>? {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return null
        synchronized(lock) {
            val entry = cache[key] ?: return null
            if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
                cache.remove(key)
                return null
            }
            return entry.songs
        }
    }

    fun put(query: String, songs: List<Song>) {
        val key = query.trim().lowercase()
        if (key.isEmpty() || songs.isEmpty()) return
        synchronized(lock) {
            cache[key] = CacheEntry(songs, System.currentTimeMillis())
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return cache.size
        }
    }
}
