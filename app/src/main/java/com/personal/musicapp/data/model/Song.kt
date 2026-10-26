package com.personal.musicapp.data.model

import com.google.gson.annotations.SerializedName

data class Song(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("artist") val artist: String = "未知原作者",
    @SerializedName("coverUrl") val coverUrl: String = "",
    @SerializedName("audioUrl") val audioUrl: String,
    @SerializedName("durationMs") val durationMs: Long = 0L,
    @SerializedName("source") val source: String = "douyin",
    @SerializedName("localAudioPath") val localAudioPath: String? = null,
    @SerializedName("localCoverPath") val localCoverPath: String? = null,
    @SerializedName("isFavorite") val isFavorite: Boolean = false,
    @SerializedName("addedTimestamp") val addedTimestamp: Long = System.currentTimeMillis()
) {
    /**
     * Returns local file path if downloaded, otherwise the online stream URL
     */
    val playableUrl: String
        get() = localAudioPath ?: audioUrl

    val displayCover: String
        get() = localCoverPath ?: coverUrl
}
