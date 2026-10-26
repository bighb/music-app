package com.personal.musicapp.data.source

import com.personal.musicapp.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class AudioSourceManager {

    private val netMusic = NetMusicAudioSource()
    private val kuwoMusic = KuwoAudioSource()
    private val douyin = DouyinAudioSource(fallbackMusicSource = netMusic)

    private val linkSources: List<AudioSource> = listOf(douyin)
    private val searchSources: List<AudioSource> = listOf(kuwoMusic, netMusic)

    fun findSourceForLink(text: String): AudioSource? {
        return linkSources.firstOrNull { it.canHandleLink(text) }
    }

    suspend fun parseShareLink(text: String): Song? {
        return douyin.parseShareLink(text)
    }

    suspend fun search(query: String): List<Song> = coroutineScope {
        val deferredKuwo = async {
            try {
                kuwoMusic.search(query)
            } catch (e: Exception) {
                emptyList()
            }
        }
        val deferredNet = async {
            try {
                netMusic.search(query)
            } catch (e: Exception) {
                emptyList()
            }
        }

        val kuwoList = deferredKuwo.await()
        val netList = deferredNet.await()

        // Interleave Kuwo (high-availability free streams) and NetMusic (rich library)
        val combined = mutableListOf<Song>()
        val maxLen = maxOf(kuwoList.size, netList.size)
        for (i in 0 until maxLen) {
            if (i < kuwoList.size) combined.add(kuwoList[i])
            if (i < netList.size) combined.add(netList[i])
        }
        combined
    }
}
