package com.personal.musicapp.data.source

import com.personal.musicapp.data.model.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class AudioSourceManager(
    private val cookieProvider: () -> String? = { null }
) {

    private val netMusic = NetMusicAudioSource()
    private val kuwoMusic = KuwoAudioSource()
    private val youtube = YouTubeAudioSource(cookieProvider)
    private val douyin = DouyinAudioSource(fallbackMusicSource = netMusic)

    private val linkSources: List<AudioSource> = listOf(douyin, youtube)
    private val searchSources: List<AudioSource> = listOf(kuwoMusic, netMusic, youtube)

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
        val deferredYt = async {
            try {
                youtube.search(query)
            } catch (e: Exception) {
                emptyList()
            }
        }

        val kuwoList = deferredKuwo.await()
        val netList = deferredNet.await()
        val ytList = deferredYt.await()

        val combined = mutableListOf<Song>()
        val maxLen = maxOf(kuwoList.size, netList.size, ytList.size)
        for (i in 0 until maxLen) {
            if (i < kuwoList.size) combined.add(kuwoList[i])
            if (i < netList.size) combined.add(netList[i])
            if (i < ytList.size) combined.add(ytList[i])
        }
        combined
    }
}
