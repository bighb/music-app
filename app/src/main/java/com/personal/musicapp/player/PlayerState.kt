package com.personal.musicapp.player

import com.personal.musicapp.data.model.Song
import com.personal.musicapp.data.source.FailureKind

data class PlayerState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isBuffering: Boolean = false,
    val playbackMode: PlaybackMode = PlaybackMode.REPEAT_ALL,
    val playlist: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val playerError: String? = null,
    val playbackFailure: PlaybackFailure? = null
)

data class PlaybackFailure(val kind: FailureKind, val detail: String) {
    val retryable: Boolean get() = kind == FailureKind.SOURCE_UNAVAILABLE
}

/** User-facing wording per failure kind. Pure: the wording table is unit-tested without a player. */
fun failureMessage(title: String, kind: FailureKind, detail: String): String = when (kind) {
    FailureKind.SOURCE_UNAVAILABLE ->
        if (detail.isBlank()) "《$title》音源暂时连不上，可稍后重试"
        else "《$title》音源暂时连不上（$detail），可稍后重试"
    FailureKind.NO_FREE_SOURCE -> "《$title》在可用免费音源里没有完整音频（可能只有会员版）"
    FailureKind.PROMO_REJECTED -> "《$title》只找到试听/推广片段，已跳过"
    FailureKind.NONE -> ""
}
