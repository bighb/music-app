package com.personal.musicapp.data.source

import com.personal.musicapp.data.model.Song
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class AudioSourceManager(
    private val cookieProvider: () -> String? = { null }
) {

    private val netMusic = NetMusicAudioSource()
    private val kuwoMusic = KuwoAudioSource()
    private val youtube = YouTubeAudioSource(cookieProvider)
    private val douyin = DouyinAudioSource(fallbackMusicSource = netMusic)

    private val suggestHelper = SearchSuggestHelper()

    private val linkSources: List<AudioSource> = listOf(douyin, youtube)
    private val searchSources: List<AudioSource> = listOf(kuwoMusic, netMusic, youtube)

    val searchCache = SearchCache()

    fun findSourceForLink(text: String): AudioSource? {
        return linkSources.firstOrNull { it.canHandleLink(text) }
    }

    suspend fun parseShareLink(text: String): Song? {
        val source = findSourceForLink(text) ?: douyin
        return source.parseShareLink(text)
    }

    suspend fun resolveYouTubeStream(videoId: String): String? {
        return youtube.resolveStreamUrl(videoId)
    }

    suspend fun getSuggestions(query: String, localHistory: List<String> = emptyList()): List<com.personal.musicapp.data.model.SearchSuggestion> {
        return suggestHelper.getSuggestions(query, localHistory)
    }

    fun searchStream(query: String): Flow<List<Song>> = channelFlow {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            send(emptyList())
            return@channelFlow
        }

        // 1. Check memory LRU cache for 0ms response
        val cached = searchCache.get(trimmed)
        if (cached != null && cached.isNotEmpty()) {
            send(cached)
            return@channelFlow
        }

        val ytSongs = mutableListOf<Song>()
        val otherSongs = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()
        val lock = Any()
        val hasEmitted = java.util.concurrent.atomic.AtomicBoolean(false)

        fun mergeAndEmit(newSongs: List<Song>) {
            if (newSongs.isEmpty()) return
            var snapshotToEmit: List<Song>? = null
            synchronized(lock) {
                var addedAny = false
                for (song in newSongs) {
                    if (seenIds.add(song.id)) {
                        if (song.source.equals("YouTube", ignoreCase = true)) {
                            ytSongs.add(song)
                        } else {
                            otherSongs.add(song)
                        }
                        addedAny = true
                    }
                }
                if (addedAny) {
                    hasEmitted.set(true)
                    // Priority ordering: YouTube results placed at the front of online results when available
                    snapshotToEmit = ytSongs + otherSongs
                }
            }
            snapshotToEmit?.let {
                trySend(it)
            }
        }

        coroutineScope {
            // Kuwo: fast domestic source (timeout 4s)
            val jobKuwo = launch {
                val results = withTimeoutOrNull(4000L) {
                    try {
                        kuwoMusic.search(trimmed)
                    } catch (e: Exception) {
                        emptyList()
                    }
                } ?: emptyList()
                mergeAndEmit(results)
            }

            // NetMusic: comprehensive domestic source (timeout 4s)
            val jobNet = launch {
                val results = withTimeoutOrNull(4000L) {
                    try {
                        netMusic.search(trimmed)
                    } catch (e: Exception) {
                        emptyList()
                    }
                } ?: emptyList()
                mergeAndEmit(results)
            }

            // YouTube: overseas source fetched asynchronously in background (timeout 3.5s)
            // Once fetched, it automatically prepends to the front of online results without blocking UI
            val jobYt = launch {
                val results = withTimeoutOrNull(3500L) {
                    try {
                        youtube.search(trimmed)
                    } catch (e: Exception) {
                        emptyList()
                    }
                } ?: emptyList()
                mergeAndEmit(results)
            }

            jobKuwo.join()
            jobNet.join()
            jobYt.join()
        }

        val finalResult = synchronized(lock) { ytSongs + otherSongs }
        if (!hasEmitted.get()) {
            send(emptyList())
        } else if (finalResult.isNotEmpty()) {
            searchCache.put(trimmed, finalResult)
        }
    }

    suspend fun search(query: String): List<Song> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val cached = searchCache.get(trimmed)
        if (cached != null) return cached
        return searchStream(trimmed).lastOrNull() ?: emptyList()
    }
}
