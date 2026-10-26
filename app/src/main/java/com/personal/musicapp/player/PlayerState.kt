package com.personal.musicapp.player

import com.personal.musicapp.data.model.Song

data class PlayerState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isBuffering: Boolean = false,
    val playbackMode: PlaybackMode = PlaybackMode.REPEAT_ALL,
    val playlist: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val playerError: String? = null
)
