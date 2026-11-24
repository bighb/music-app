package com.personal.musicapp.data.source

import com.personal.musicapp.data.model.Song

interface AudioSource {
    val sourceName: String

    /** End-to-end budget for one search, owned by the source (mirrors its OkHttp callTimeout). */
    val searchDeadlineMs: Long
        get() = HttpPolicy.searchDeadlineMs(sourceName)

    suspend fun search(query: String): List<Song>

    suspend fun search(query: String, reporter: HttpReporter): List<Song> = search(query)

    suspend fun parseShareLink(text: String): Song?
    fun canHandleLink(text: String): Boolean
}
