package com.personal.musicapp.data.source

import com.personal.musicapp.data.model.Song

class KuwoAudioSource : AudioSource {

    override val sourceName: String = "酷我"

    override fun canHandleLink(text: String): Boolean = false

    override suspend fun parseShareLink(text: String): Song? = null

    override suspend fun search(query: String): List<Song> {
        return KuwoAudioHelper.searchSongs(query)
    }
}
