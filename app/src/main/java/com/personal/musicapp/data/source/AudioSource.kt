package com.personal.musicapp.data.source

import com.personal.musicapp.data.model.Song

interface AudioSource {
    val sourceName: String
    suspend fun search(query: String): List<Song>
    suspend fun parseShareLink(text: String): Song?
    fun canHandleLink(text: String): Boolean
}
