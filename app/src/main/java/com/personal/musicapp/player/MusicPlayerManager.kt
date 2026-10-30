package com.personal.musicapp.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.personal.musicapp.MusicApplication
import com.personal.musicapp.data.model.Song
import com.personal.musicapp.data.source.KuwoAudioHelper
import com.personal.musicapp.data.source.YouTubeAudioSource
import com.personal.musicapp.util.AppLogger
import com.personal.musicapp.util.awaitResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class MusicPlayerManager(private val context: Context) {

    private val tag = "MusicPlayerManager"
    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null
    private var prepareJob: Job? = null

    private val redirectResolverClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(5000, TimeUnit.MILLISECONDS)
        .readTimeout(5000, TimeUnit.MILLISECONDS)
        .build()

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(YouTubeAudioSource.SAFARI_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15000)
        .setReadTimeoutMs(20000)

    // Wraps httpDataSourceFactory to support file:// (local downloads/cache), content:// and http(s)://
    private val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            AppLogger.d(tag, "onIsPlayingChanged: $isPlaying")
            _playerState.update { it.copy(isPlaying = isPlaying, playerError = null) }
            if (isPlaying) {
                startProgressTracker()
                // Record to play history on active playback
                _playerState.value.currentSong?.let { song ->
                    MusicApplication.instance.localRepository.recordPlayback(song)
                }
            } else {
                stopProgressTracker()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val isBuffering = playbackState == Player.STATE_BUFFERING
            val duration = if (exoPlayer.duration > 0) exoPlayer.duration else _playerState.value.durationMs
            AppLogger.d(tag, "onPlaybackStateChanged: state=$playbackState, duration=$duration")
            _playerState.update {
                it.copy(
                    isBuffering = isBuffering,
                    durationMs = duration
                )
            }
            if (playbackState == Player.STATE_READY) {
                _playerState.value.currentSong?.let { song ->
                    MusicApplication.instance.localRepository.recordPlayback(song)
                }
            }
            if (playbackState == Player.STATE_ENDED) {
                handlePlaybackEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val errorMsg = "播放出错: ${error.errorCodeName} (${error.errorCode}) - ${error.localizedMessage ?: "未知解码或网络问题"}"
            AppLogger.e(tag, "ExoPlayer Error for 《${_playerState.value.currentSong?.title}》: $errorMsg", error)
            try {
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            } catch (_: Exception) {}
            _playerState.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    playerError = errorMsg
                )
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            _playerState.update {
                it.copy(currentPositionMs = newPosition.positionMs)
            }
        }
    }

    init {
        exoPlayer.addListener(playerListener)
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val currentPos = exoPlayer.currentPosition
                val duration = if (exoPlayer.duration > 0) exoPlayer.duration else _playerState.value.durationMs
                _playerState.update {
                    it.copy(
                        currentPositionMs = currentPos,
                        durationMs = duration
                    )
                }
                delay(300)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    fun playSong(song: Song, playlist: List<Song> = listOf(song), autoSkipOnFailure: Boolean = true) {
        val index = playlist.indexOfFirst { it.id == song.id }.let { if (it == -1) 0 else it }
        _playerState.update {
            it.copy(
                currentSong = song,
                playlist = playlist,
                currentIndex = index,
                currentPositionMs = 0L,
                durationMs = song.durationMs,
                isBuffering = true,
                playerError = null
            )
        }

        prepareJob?.cancel()
        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        } catch (_: Exception) {}

        prepareJob = scope.launch {
            try {
                AppLogger.i(tag, "Preparing to play: 《${song.title}》 - ${song.artist}")

                // Check local cached file first
                var playFromLocal = false
                var playUri: Uri? = null

                val effectiveAudioPath = song.localAudioPath ?: MusicApplication.instance.localRepository.downloads.value.find { it.id == song.id }?.localAudioPath

                if (!effectiveAudioPath.isNullOrEmpty()) {
                    val localFile = File(effectiveAudioPath)
                    if (localFile.exists() && localFile.length() >= 10000) {
                        playFromLocal = true
                        playUri = Uri.fromFile(localFile)
                        AppLogger.i(tag, "Playing 《${song.title}》 from verified local cache: ${localFile.absolutePath} (${localFile.length() / 1024} KB)")
                    } else {
                        AppLogger.w(tag, "Local cache file missing or corrupted for 《${song.title}》, falling back to network stream")
                        if (localFile.exists()) localFile.delete()
                    }
                }

                if (!playFromLocal || playUri == null) {
                    val directPlayUrl = resolveMediaDirectUrl(song)
                    if (directPlayUrl.isNullOrEmpty()) {
                        val errorMsg = "《${song.title}》暂无可用音源（受版权保护）"
                        AppLogger.w(tag, errorMsg)
                        _playerState.update {
                            it.copy(
                                isBuffering = false,
                                isPlaying = false,
                                playerError = errorMsg
                            )
                        }
                        if (autoSkipOnFailure && playlist.size > 1) {
                            delay(1500)
                            skipNext()
                        }
                        return@launch
                    }
                    AppLogger.i(tag, "Resolved direct stream URL: $directPlayUrl")
                    playUri = Uri.parse(directPlayUrl)
                }

                val effectiveCoverPath = song.localCoverPath ?: MusicApplication.instance.localRepository.downloads.value.find { it.id == song.id }?.localCoverPath
                val artworkUri = when {
                    effectiveCoverPath != null && File(effectiveCoverPath).exists() -> {
                        Uri.fromFile(File(effectiveCoverPath))
                    }
                    song.coverUrl.isNotEmpty() -> Uri.parse(song.coverUrl)
                    else -> null
                }

                val mediaMetadata = MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(artworkUri)
                    .build()

                val mediaItem = MediaItem.Builder()
                    .setMediaId(song.id)
                    .setUri(playUri)
                    .setMediaMetadata(mediaMetadata)
                    .build()

                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.play()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val errorMsg = "《${song.title}》播放异常: ${e.localizedMessage ?: "网络或解码错误"}"
                AppLogger.e(tag, errorMsg, e)
                _playerState.update {
                    it.copy(
                        isBuffering = false,
                        isPlaying = false,
                        playerError = errorMsg
                    )
                }
                if (autoSkipOnFailure && playlist.size > 1) {
                    delay(1500)
                    skipNext()
                }
            }
        }
    }

    private suspend fun resolveMediaDirectUrl(song: Song): String? = withContext(Dispatchers.IO) {
        val rawUrl = song.audioUrl
        if (!rawUrl.startsWith("http") && !rawUrl.startsWith("kuwo://") && !rawUrl.startsWith("youtube://")) return@withContext rawUrl

        // 0. YouTube protocol (youtube://$videoId)
        if (rawUrl.startsWith("youtube://")) {
            val videoId = rawUrl.removePrefix("youtube://")
            try {
                val streamUrl = MusicApplication.instance.audioSourceManager.resolveYouTubeStream(videoId)
                if (!streamUrl.isNullOrEmpty() && streamUrl.startsWith("http")) {
                    return@withContext streamUrl
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "YouTube stream resolve failed for $videoId", e)
            }

            // Fallback: search Kuwo by title if YouTube stream fails
            val fallbackUrl = KuwoAudioHelper.getPlayableUrl(song.title, song.artist)
            if (!fallbackUrl.isNullOrEmpty()) {
                AppLogger.i(tag, "Fallback from YouTube to Kuwo search stream for 《${song.title}》")
                return@withContext fallbackUrl
            }
            return@withContext null
        }

        // 1. Kuwo protocol (kuwo://$rid)
        if (rawUrl.startsWith("kuwo://")) {
            val rid = rawUrl.removePrefix("kuwo://")
            val convertUrl = "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=$rid&format=mp3&response=url"
            try {
                val req = Request.Builder().url(convertUrl).build()
                redirectResolverClient.newCall(req).awaitResponse().use { resp ->
                    val streamUrl = resp.body?.string()?.trim()
                    if (!streamUrl.isNullOrEmpty() && streamUrl.startsWith("http")) {
                        if (KuwoAudioHelper.isKuwoPromoAudio(streamUrl)) {
                            AppLogger.w(tag, "Kuwo stream for rid $rid is copyright promo audio (588957081.mp3), ignoring")
                        } else {
                            return@withContext streamUrl
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "Kuwo convert error for rid $rid", e)
            }

            // Fallback: search Kuwo by title if direct rid fails
            val fallbackUrl = KuwoAudioHelper.getPlayableUrl(song.title, song.artist)
            if (!fallbackUrl.isNullOrEmpty()) {
                AppLogger.i(tag, "Fallback to Kuwo search stream for 《${song.title}》")
                return@withContext fallbackUrl
            }
            return@withContext null
        }

        // 2. Netease outer URL
        if (rawUrl.contains("music.163.com/song/media/outer/url")) {
            try {
                val req = Request.Builder()
                    .url(rawUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                redirectResolverClient.newCall(req).awaitResponse().use { resp ->
                    val location = resp.header("Location")
                    if (!location.isNullOrEmpty()) {
                        // Check if Netease redirected to 404 (VIP or copyrighted)
                        if (!location.contains("/404") && !location.contains("404.html")) {
                            return@withContext location.replace("http://", "https://")
                        } else {
                            AppLogger.w(tag, "Song 《${song.title}》 redirected to 404 on Netease, cascading to Kuwo fallback...")
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "Failed to resolve redirect for $rawUrl", e)
            }

            // Fallback to Kuwo for 404 songs!
            val fallbackUrl = KuwoAudioHelper.getPlayableUrl(song.title, song.artist)
            if (!fallbackUrl.isNullOrEmpty()) {
                AppLogger.i(tag, "Recovered 《${song.title}》 via Kuwo fallback stream")
                return@withContext fallbackUrl
            }

            return@withContext null
        }

        return@withContext rawUrl
    }

    // ==================== Playlist Queue Management ====================

    fun playNext(song: Song) {
        val state = _playerState.value
        if (state.currentSong == null || state.playlist.isEmpty()) {
            playSong(song, listOf(song))
            return
        }

        val newPlaylist = state.playlist.toMutableList()
        // Remove existing instance if already in playlist
        val existingIndex = newPlaylist.indexOfFirst { it.id == song.id }
        if (existingIndex != -1) {
            newPlaylist.removeAt(existingIndex)
        }

        // Calculate insertion point (right after current index)
        val currentIdx = newPlaylist.indexOfFirst { it.id == state.currentSong.id }
        val insertPos = if (currentIdx != -1) currentIdx + 1 else newPlaylist.size
        newPlaylist.add(insertPos, song)

        val updatedCurrentIndex = newPlaylist.indexOfFirst { it.id == state.currentSong.id }
        _playerState.update {
            it.copy(
                playlist = newPlaylist,
                currentIndex = if (updatedCurrentIndex != -1) updatedCurrentIndex else it.currentIndex
            )
        }
        AppLogger.i(tag, "Queued 《${song.title}》 to play next")
    }

    fun addToPlaylist(song: Song) {
        val state = _playerState.value
        if (state.currentSong == null || state.playlist.isEmpty()) {
            playSong(song, listOf(song))
            return
        }

        val newPlaylist = state.playlist.toMutableList()
        // If not already in playlist, append to end
        if (!newPlaylist.any { it.id == song.id }) {
            newPlaylist.add(song)
            _playerState.update { it.copy(playlist = newPlaylist) }
            AppLogger.i(tag, "Added 《${song.title}》 to end of playlist")
        } else {
            AppLogger.d(tag, "《${song.title}》 already in playlist")
        }
    }

    fun removeFromPlaylist(songId: String) {
        val state = _playerState.value
        val existingIndex = state.playlist.indexOfFirst { it.id == songId }
        if (existingIndex == -1) return

        if (songId == state.currentSong?.id) {
            if (state.playlist.size <= 1) {
                try {
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                } catch (_: Exception) {}
                _playerState.update {
                    it.copy(
                        currentSong = null,
                        playlist = emptyList(),
                        currentIndex = 0,
                        isPlaying = false,
                        isBuffering = false,
                        currentPositionMs = 0L,
                        durationMs = 0L,
                        playerError = null
                    )
                }
                AppLogger.i(tag, "Removed only song from playlist, playback stopped")
                return
            }

            val nextIndex = (existingIndex + 1) % state.playlist.size
            val nextSong = state.playlist[nextIndex]
            val newPlaylist = state.playlist.toMutableList()
            newPlaylist.removeAt(existingIndex)
            playSong(nextSong, newPlaylist)
            AppLogger.i(tag, "Removed currently playing song $songId, advanced to 《${nextSong.title}》")
        } else {
            val newPlaylist = state.playlist.toMutableList()
            newPlaylist.removeAt(existingIndex)
            val updatedCurrentIndex = newPlaylist.indexOfFirst { it.id == state.currentSong?.id }
            _playerState.update {
                it.copy(
                    playlist = newPlaylist,
                    currentIndex = if (updatedCurrentIndex != -1) updatedCurrentIndex else 0
                )
            }
            AppLogger.i(tag, "Removed song $songId from playlist")
        }
    }

    fun reorderPlaylist(fromIndex: Int, toIndex: Int) {
        val state = _playerState.value
        if (fromIndex !in state.playlist.indices || toIndex !in state.playlist.indices || fromIndex == toIndex) return

        val newPlaylist = state.playlist.toMutableList()
        val movedItem = newPlaylist.removeAt(fromIndex)
        newPlaylist.add(toIndex, movedItem)

        val updatedCurrentIndex = newPlaylist.indexOfFirst { it.id == state.currentSong?.id }
        _playerState.update {
            it.copy(
                playlist = newPlaylist,
                currentIndex = if (updatedCurrentIndex != -1) updatedCurrentIndex else it.currentIndex
            )
        }
        AppLogger.d(tag, "Reordered playlist: moved from $fromIndex to $toIndex")
    }

    fun clearPlaylist() {
        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        } catch (_: Exception) {}
        _playerState.update {
            it.copy(
                currentSong = null,
                playlist = emptyList(),
                currentIndex = 0,
                isPlaying = false,
                isBuffering = false,
                currentPositionMs = 0L,
                durationMs = 0L,
                playerError = null
            )
        }
        AppLogger.i(tag, "Cleared playlist queue")
    }

    fun insertSongIntoPlaylist(index: Int, song: Song) {
        val state = _playerState.value
        if (state.playlist.isEmpty() && state.currentSong == null) {
            playSong(song, listOf(song))
            return
        }
        val newPlaylist = state.playlist.toMutableList()
        val safeIndex = index.coerceIn(0, newPlaylist.size)
        if (!newPlaylist.any { it.id == song.id }) {
            newPlaylist.add(safeIndex, song)
            val updatedCurrentIndex = newPlaylist.indexOfFirst { it.id == state.currentSong?.id }
            _playerState.update {
                it.copy(
                    playlist = newPlaylist,
                    currentIndex = if (updatedCurrentIndex != -1) updatedCurrentIndex else it.currentIndex
                )
            }
            AppLogger.i(tag, "Restored 《${song.title}》 to playlist at index $safeIndex")
        }
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playbackState == Player.STATE_IDLE && _playerState.value.currentSong != null) {
                _playerState.value.currentSong?.let { playSong(it, _playerState.value.playlist) }
            } else {
                exoPlayer.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        _playerState.update { it.copy(currentPositionMs = positionMs) }
    }

    fun skipNext() {
        val state = _playerState.value
        if (state.playlist.isEmpty()) return

        val nextIndex = when (state.playbackMode) {
            PlaybackMode.SHUFFLE -> {
                if (state.playlist.size == 1) 0
                else {
                    var rand = (state.playlist.indices).random()
                    if (rand == state.currentIndex && state.playlist.size > 1) {
                        rand = (rand + 1) % state.playlist.size
                    }
                    rand
                }
            }
            else -> (state.currentIndex + 1) % state.playlist.size
        }

        val nextSong = state.playlist[nextIndex]
        playSong(nextSong, state.playlist)
    }

    fun skipPrevious() {
        val state = _playerState.value
        if (state.playlist.isEmpty()) return

        if (exoPlayer.currentPosition > 3000) {
            seekTo(0)
            return
        }

        val prevIndex = if (state.currentIndex <= 0) {
            state.playlist.size - 1
        } else {
            state.currentIndex - 1
        }

        val prevSong = state.playlist[prevIndex]
        playSong(prevSong, state.playlist)
    }

    fun togglePlaybackMode() {
        _playerState.update { it.copy(playbackMode = it.playbackMode.next()) }
    }

    private fun handlePlaybackEnded() {
        val state = _playerState.value
        when (state.playbackMode) {
            PlaybackMode.REPEAT_ONE -> {
                seekTo(0)
                exoPlayer.play()
            }
            PlaybackMode.REPEAT_ALL, PlaybackMode.SHUFFLE -> {
                skipNext()
            }
        }
    }

    fun release() {
        stopProgressTracker()
        prepareJob?.cancel()
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
    }
}
