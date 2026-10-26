package com.personal.musicapp.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.personal.musicapp.data.model.Song
import com.personal.musicapp.data.source.KuwoAudioHelper
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
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15000)
        .setReadTimeoutMs(20000)

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory))
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
            Log.d(tag, "onIsPlayingChanged: $isPlaying")
            _playerState.update { it.copy(isPlaying = isPlaying, playerError = null) }
            if (isPlaying) {
                startProgressTracker()
            } else {
                stopProgressTracker()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val isBuffering = playbackState == Player.STATE_BUFFERING
            val duration = if (exoPlayer.duration > 0) exoPlayer.duration else _playerState.value.durationMs
            Log.d(tag, "onPlaybackStateChanged: state=$playbackState, duration=$duration")
            _playerState.update {
                it.copy(
                    isBuffering = isBuffering,
                    durationMs = duration
                )
            }
            if (playbackState == Player.STATE_ENDED) {
                handlePlaybackEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val errorMsg = "播放出错: ${error.errorCodeName} (${error.errorCode}) - ${error.localizedMessage ?: "未知解码或网络问题"}"
            Log.e(tag, "ExoPlayer Error: $errorMsg", error)
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

    fun playSong(song: Song, playlist: List<Song> = listOf(song)) {
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
        prepareJob = scope.launch {
            // Resolve direct playable stream URL (with 404 protection & Kuwo fallback)
            val directPlayUrl = resolveMediaDirectUrl(song)
            if (directPlayUrl.isNullOrEmpty()) {
                val errorMsg = "该歌曲受版权或VIP限制，暂无免费可用音源"
                _playerState.update {
                    it.copy(
                        isBuffering = false,
                        isPlaying = false,
                        playerError = errorMsg
                    )
                }
                return@launch
            }

            Log.d(tag, "Playing ${song.title} from: $directPlayUrl")

            val playUri = when {
                song.localAudioPath != null && File(song.localAudioPath).exists() -> {
                    Uri.fromFile(File(song.localAudioPath))
                }
                else -> Uri.parse(directPlayUrl)
            }

            val artworkUri = when {
                song.localCoverPath != null && File(song.localCoverPath).exists() -> {
                    Uri.fromFile(File(song.localCoverPath))
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
        }
    }

    private suspend fun resolveMediaDirectUrl(song: Song): String? = withContext(Dispatchers.IO) {
        val rawUrl = song.playableUrl
        if (!rawUrl.startsWith("http") && !rawUrl.startsWith("kuwo://")) return@withContext rawUrl

        // 1. Kuwo protocol (kuwo://$rid)
        if (rawUrl.startsWith("kuwo://")) {
            val rid = rawUrl.removePrefix("kuwo://")
            val convertUrl = "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=$rid&format=mp3&response=url"
            try {
                val req = Request.Builder().url(convertUrl).build()
                redirectResolverClient.newCall(req).execute().use { resp ->
                    val streamUrl = resp.body?.string()?.trim()
                    if (!streamUrl.isNullOrEmpty() && streamUrl.startsWith("http")) {
                        return@withContext streamUrl
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Kuwo convert error for $rid", e)
            }
        }

        // 2. Netease outer URL
        if (rawUrl.contains("music.163.com/song/media/outer/url")) {
            try {
                val req = Request.Builder()
                    .url(rawUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                redirectResolverClient.newCall(req).execute().use { resp ->
                    val location = resp.header("Location")
                    if (!location.isNullOrEmpty()) {
                        // Check if Netease redirected to 404 (VIP or copyrighted)
                        if (!location.contains("/404") && !location.contains("404.html")) {
                            return@withContext location.replace("http://", "https://")
                        } else {
                            Log.w(tag, "Song ${song.title} redirected to 404 on Netease, cascading to Kuwo fallback...")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to resolve redirect for $rawUrl", e)
            }

            // Fallback to Kuwo for 404 songs!
            val fallbackUrl = KuwoAudioHelper.getPlayableUrl(song.title, song.artist)
            if (!fallbackUrl.isNullOrEmpty()) {
                return@withContext fallbackUrl
            }

            // Return null if all sources fail, so ExoPlayer never receives an HTML 404 page
            return@withContext null
        }

        return@withContext rawUrl
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
