package com.personal.musicapp.data.source

import com.personal.musicapp.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class AudioSourceManager {

    private val netMusic = NetMusicAudioSource()
    private val douyin = DouyinAudioSource(fallbackMusicSource = netMusic)

    private val sources: List<AudioSource> = listOf(douyin, netMusic)

    fun findSourceForLink(text: String): AudioSource? {
        return sources.firstOrNull { it.canHandleLink(text) }
    }

    suspend fun parseShareLink(text: String): Song? {
        return douyin.parseShareLink(text)
    }

    suspend fun search(query: String): List<Song> = coroutineScope {
        val deferredList = sources.map { src ->
            async {
                try {
                    src.search(query)
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
        deferredList.awaitAll().flatten()
    }
}
