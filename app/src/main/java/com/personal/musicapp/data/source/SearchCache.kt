package com.personal.musicapp.data.source

import com.personal.musicapp.data.model.Song

/**
 * Two TTLs: a complete snapshot is authoritative for [ttlMs], a degraded one only for
 * [partialTtlMs] so a dead network is not replayed as "this catalogue has nothing".
 */
class SearchCache(
    private val maxSize: Int = 50,
    private val ttlMs: Long = 10 * 60 * 1000L,
    private val partialTtlMs: Long = 20 * 1000L,
    private val timeProvider: () -> Long = System::currentTimeMillis
) {

    data class CacheHit(val songs: List<Song>, val complete: Boolean)

    private data class CacheEntry(
        val songs: List<Song>,
        val timestamp: Long,
        val complete: Boolean
    )

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, CacheEntry>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxSize
        }
    }

    private fun ttlFor(entry: CacheEntry): Long = if (entry.complete) ttlMs else partialTtlMs

    fun getHit(query: String): CacheHit? {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return null
        synchronized(lock) {
            val entry = cache[key] ?: return null
            if (timeProvider() - entry.timestamp > ttlFor(entry)) {
                cache.remove(key)
                return null
            }
            return CacheHit(entry.songs, entry.complete)
        }
    }

    fun get(query: String): List<Song>? = getHit(query)?.songs

    fun put(query: String, songs: List<Song>, complete: Boolean = true) {
        val key = query.trim().lowercase()
        if (key.isEmpty() || songs.isEmpty()) return
        synchronized(lock) {
            cache[key] = CacheEntry(songs, timeProvider(), complete)
        }
    }

    fun remove(query: String) {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return
        synchronized(lock) {
            cache.remove(key)
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
