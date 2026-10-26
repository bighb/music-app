package com.personal.musicapp.player

enum class PlaybackMode {
    REPEAT_ALL,
    REPEAT_ONE,
    SHUFFLE;

    fun next(): PlaybackMode {
        return when (this) {
            REPEAT_ALL -> REPEAT_ONE
            REPEAT_ONE -> SHUFFLE
            SHUFFLE -> REPEAT_ALL
        }
    }
}
