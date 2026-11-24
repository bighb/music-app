package com.personal.musicapp.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.personal.musicapp.MusicApplication
import com.personal.musicapp.data.model.Song
import com.personal.musicapp.data.source.AudioFileVerifier
import com.personal.musicapp.data.source.FailureKind
import com.personal.musicapp.data.source.HttpPolicy
import com.personal.musicapp.data.source.PlaceholderGate
import com.personal.musicapp.data.source.SourceDiagnostics
import com.personal.musicapp.data.source.StreamResolveException
import com.personal.musicapp.util.AppLogger
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * ExoPlayer owns the real queue: every song is a MediaItem whose URI is the source's
 * placeholder (`kuwo://rid`, `youtube://id`, Netease outer link) and is resolved to a stream
 * URL only when its bytes are first read. That is what makes skip-to-next work for Bluetooth
 * AVRCP and the notification: the timeline the system sees is the actual playlist.
 */
class MusicPlayerManager(private val context: Context) {

    private val tag = "MusicPlayerManager"
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private var progressJob: Job? = null
    private var autoSkipJob: Job? = null
    private var lastPrefetchedSongId: String? = null

    @Volatile
    private var placeholderRejectedFor: String? = null
    private var staleLinkRetriedFor: String? = null
    private var consecutiveFailures = 0

    /** Placeholder-URI string -> Song. Written on main, read on ExoPlayer's loading thread. */
    private val songsByUri = ConcurrentHashMap<String, Song>()

    companion object {
        private const val AUTO_SKIP_DELAY_MS = 1500L
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val PREVIOUS_RESTART_MS = 3000L
        private const val PRELOAD_WINDOW_MS = 15000L

        @Volatile
        private var simpleCacheInstance: SimpleCache? = null

        fun getSimpleCache(context: Context): SimpleCache {
            return simpleCacheInstance ?: synchronized(this) {
                simpleCacheInstance ?: run {
                    val cacheDir = File(context.cacheDir, "media_cache")
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    val databaseProvider = StandaloneDatabaseProvider(context)
                    val evictor = LeastRecentlyUsedCacheEvictor(500 * 1024 * 1024L) // 500 MB LRU Cache
                    SimpleCache(cacheDir, evictor, databaseProvider).also {
                        simpleCacheInstance = it
                    }
                }
            }
        }
    }

    private val simpleCache = getSimpleCache(context)

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(HttpPolicy.YOUTUBE_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15000)
        .setReadTimeoutMs(20000)

    private val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(simpleCache)
        .setUpstreamDataSourceFactory(httpDataSourceFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val streamResolver get() = MusicApplication.instance.streamResolver

    /** Runs on ExoPlayer's loading thread; bounded by the resolver's own per-call and total deadlines. */
    private val placeholderResolver = object : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val placeholder = dataSpec.uri.toString()
            val song = songsByUri[placeholder] ?: return dataSpec
            val result = runBlocking { streamResolver.resolve(song) }
            SourceDiagnostics.logResolve(result, song.id, song.title, song.source)
            val stream = result.stream ?: throw StreamResolveException(result.failure, result.describeFailures())
            return dataSpec.withUri(Uri.parse(stream.url))
        }
    }

    // ResolvingDataSource -> CacheDataSource -> DefaultHttpDataSource, wrapped so that
    // file:// (local downloads), content:// and plain http(s):// keep working unchanged.
    private val dataSourceFactory = DefaultDataSource.Factory(
        context,
        ResolvingDataSource.Factory(cacheDataSourceFactory, placeholderResolver)
    )

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

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            AppLogger.d(tag, "onIsPlayingChanged: $isPlaying")
            _playerState.update { it.copy(isPlaying = isPlaying, playerError = null, playbackFailure = null) }
            if (isPlaying) {
                consecutiveFailures = 0
                startProgressTracker()
                _playerState.value.currentSong?.let { recordPlayback(it) }
            } else {
                stopProgressTracker()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val song = mediaItem?.mediaId?.let { id -> _playerState.value.playlist.find { it.id == id } } ?: return
            val index = _playerState.value.playlist.indexOfFirst { it.id == song.id }
            if (index == -1) return
            lastPrefetchedSongId = null
            AppLogger.i(tag, "Now playing #$index 《${song.title}》 (${song.source}) reason=$reason")
            _playerState.update {
                it.copy(
                    currentSong = song,
                    currentIndex = index,
                    currentPositionMs = 0L,
                    durationMs = song.durationMs,
                    isBuffering = true,
                    playerError = null,
                    playbackFailure = null
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val isBuffering = playbackState == Player.STATE_BUFFERING
            val duration = if (exoPlayer.duration > 0) exoPlayer.duration else _playerState.value.durationMs
            AppLogger.d(tag, "onPlaybackStateChanged: state=$playbackState, duration=$duration")
            _playerState.update { it.copy(isBuffering = isBuffering, durationMs = duration) }
            if (playbackState == Player.STATE_READY) {
                if (rejectPlaceholderStream()) return
                _playerState.value.currentSong?.let { recordPlayback(it) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            _playerState.update { it.copy(currentPositionMs = newPosition.positionMs) }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            val playlist = _playerState.value.playlist
            val index = player.currentMediaItemIndex
            val currentId = player.currentMediaItem?.mediaId
            if (index in playlist.indices && playlist[index].id == currentId && index != _playerState.value.currentIndex) {
                _playerState.update { it.copy(currentIndex = index, currentSong = playlist[index]) }
            }

            // The car head unit and the notification may change repeat/shuffle themselves.
            val mirrored = when {
                player.shuffleModeEnabled -> PlaybackMode.SHUFFLE
                player.repeatMode == Player.REPEAT_MODE_ONE -> PlaybackMode.REPEAT_ONE
                else -> PlaybackMode.REPEAT_ALL
            }
            if (mirrored != _playerState.value.playbackMode) {
                AppLogger.i(tag, "Playback mode changed by a controller: ${mirrored.name}")
                _playerState.update { it.copy(playbackMode = mirrored) }
            }
        }
    }

    init {
        exoPlayer.addListener(playerListener)
        applyPlaybackMode(_playerState.value.playbackMode)
    }

    private fun recordPlayback(song: Song) {
        MusicApplication.instance.localRepository.recordPlayback(song)
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val currentPos = exoPlayer.currentPosition
                val duration = if (exoPlayer.duration > 0) exoPlayer.duration else _playerState.value.durationMs
                _playerState.update { it.copy(currentPositionMs = currentPos, durationMs = duration) }
                checkAndTriggerPreload(currentPos, duration)
                delay(300)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun checkAndTriggerPreload(currentPos: Long, duration: Long) {
        if (duration <= PRELOAD_WINDOW_MS || exoPlayer.mediaItemCount <= 1) return
        val remaining = duration - currentPos
        val progressRatio = currentPos.toFloat() / duration
        if (remaining > PRELOAD_WINDOW_MS && progressRatio < 0.85f) return

        if (exoPlayer.repeatMode == Player.REPEAT_MODE_ONE) return
        val nextIndex = exoPlayer.nextMediaItemIndex.takeIf { it >= 0 } ?: return
        val currentId = _playerState.value.currentSong?.id
        val nextSong = _playerState.value.playlist.getOrNull(nextIndex) ?: return
        if (nextSong.id == lastPrefetchedSongId || nextSong.id == currentId) return
        lastPrefetchedSongId = nextSong.id
        prefetchNextTrack(nextSong)
    }

    fun prefetchNextTrack(song: Song) {
        if (streamResolver.peek(song.id) != null) return
        if (localAudioFile(song) != null) return
        scope.launch(Dispatchers.IO) {
            try {
                val result = streamResolver.resolve(song)
                val outcome = result.stream?.let { "ok via ${it.sourceUsed}" } ?: result.failure.name.lowercase().replace('_', '-')
                AppLogger.v(tag, "Pre-resolve 《${song.title}》: $outcome")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.v(tag, "Pre-resolve 《${song.title}》 failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // ==================== Queue <-> ExoPlayer timeline ====================

    private fun localAudioFile(song: Song): File? {
        val path = song.localAudioPath
            ?: MusicApplication.instance.localRepository.downloads.value.find { it.id == song.id }?.localAudioPath
            ?: return null
        val file = File(path)
        if (AudioFileVerifier.isPlausible(file, song.durationMs)) return file
        if (file.exists()) {
            AppLogger.w(tag, "Local audio for 《${song.title}》 is implausibly small; using the network stream")
            file.delete()
        }
        return null
    }

    private fun toMediaItem(song: Song): MediaItem {
        val localFile = localAudioFile(song)
        val uri = if (localFile != null) Uri.fromFile(localFile) else Uri.parse(song.audioUrl)
        songsByUri[uri.toString()] = song

        val coverPath = song.localCoverPath
            ?: MusicApplication.instance.localRepository.downloads.value.find { it.id == song.id }?.localCoverPath
        val artworkUri = when {
            coverPath != null && File(coverPath).exists() -> Uri.fromFile(File(coverPath))
            song.coverUrl.isNotEmpty() -> Uri.parse(song.coverUrl)
            else -> null
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setArtworkUri(artworkUri)
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun queuedIds(): List<String> =
        List(exoPlayer.mediaItemCount) { exoPlayer.getMediaItemAt(it).mediaId }

    private fun queueMatches(playlist: List<Song>): Boolean {
        if (exoPlayer.mediaItemCount != playlist.size) return false
        for (i in playlist.indices) {
            if (exoPlayer.getMediaItemAt(i).mediaId != playlist[i].id) return false
        }
        return true
    }

    /** Applies one queue edit in place so the current song keeps playing; rebuilds if the result differs. */
    private fun editQueue(expectedIds: List<String>, edit: () -> Unit) {
        try {
            edit()
        } catch (e: RuntimeException) {
            AppLogger.w(tag, "Queue edit rejected (${e.javaClass.simpleName}); rebuilding timeline from state")
            rebuildQueue()
            return
        }
        if (queuedIds() != expectedIds) rebuildQueue()
    }

    /** Re-applies a single-row insert / removal / move to the ExoPlayer timeline. */
    private fun syncQueue(oldIds: List<String>, newIds: List<String>, newPlaylist: List<Song>) {
        when (val edit = queueEditFrom(oldIds, newIds)) {
            is QueueEdit.Insert -> exoPlayer.addMediaItem(edit.index, toMediaItem(newPlaylist[edit.index]))
            is QueueEdit.Remove -> exoPlayer.removeMediaItem(edit.index)
            is QueueEdit.Move -> {
                val movingCurrent = oldIds.getOrNull(edit.from) != null &&
                    oldIds[edit.from] == exoPlayer.currentMediaItem?.mediaId
                if (movingCurrent) {
                    exoPlayer.moveMediaItem(edit.from, edit.to)
                } else {
                    // Remove + add has an unambiguous index meaning; moveMediaItem would make the
                    // player advance to the next song if the moved row were the current one.
                    val item = exoPlayer.getMediaItemAt(edit.from)
                    exoPlayer.removeMediaItem(edit.from)
                    exoPlayer.addMediaItem(edit.to, item)
                }
            }
            QueueEdit.None -> Unit
            QueueEdit.Rebuild -> throw IllegalStateException("queue changed shape")
        }
    }

    private fun rebuildQueue() {
        val state = _playerState.value
        if (state.playlist.isEmpty()) return
        val wasPlaying = state.isPlaying || exoPlayer.playWhenReady
        val index = state.currentIndex.coerceIn(0, state.playlist.lastIndex)
        exoPlayer.stop()
        exoPlayer.setMediaItems(state.playlist.map { toMediaItem(it) }, index, 0L)
        exoPlayer.prepare()
        if (wasPlaying) exoPlayer.play()
    }

    private fun commitPlaylist(newPlaylist: List<Song>) {
        val oldIds = queuedIds()
        _playerState.update {
            val idx = newPlaylist.indexOfFirst { s -> s.id == it.currentSong?.id }
            it.copy(playlist = newPlaylist, currentIndex = if (idx != -1) idx else 0)
        }
        val newIds = newPlaylist.map { it.id }
        editQueue(newIds) { syncQueue(oldIds, newIds, newPlaylist) }
    }

    private fun applyPlaybackMode(mode: PlaybackMode) {
        exoPlayer.repeatMode = if (mode == PlaybackMode.REPEAT_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
        exoPlayer.shuffleModeEnabled = mode == PlaybackMode.SHUFFLE
    }

    private fun playAtIndex(index: Int) {
        val state = _playerState.value
        if (state.playlist.isEmpty()) return
        if (!queueMatches(state.playlist)) rebuildQueue()
        if (exoPlayer.mediaItemCount == 0) return
        placeholderRejectedFor = null
        exoPlayer.seekTo(index.coerceIn(0, exoPlayer.mediaItemCount - 1), 0L)
        if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
        exoPlayer.play()
    }

    private fun stopAndClearQueue() {
        try {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        } catch (_: Exception) {
        }
        songsByUri.clear()
        _playerState.update {
            it.copy(
                currentSong = null,
                playlist = emptyList(),
                currentIndex = 0,
                isPlaying = false,
                isBuffering = false,
                currentPositionMs = 0L,
                durationMs = 0L,
                playerError = null,
                playbackFailure = null
            )
        }
    }

    // ==================== Failure handling ====================

    /**
     * A stream that is far shorter than its catalogue entry is a preview placeholder. The
     * URL is blacklisted and the same queue position is prepared again, so the resolver
     * continues down its chain. Runs at most once per song.
     */
    private fun rejectPlaceholderStream(): Boolean {
        val state = _playerState.value
        val song = state.currentSong ?: return false
        if (placeholderRejectedFor == song.id) return false
        val actual = exoPlayer.duration
        if (!PlaceholderGate.isDurationMismatch(song.durationMs, actual)) return false

        placeholderRejectedFor = song.id
        streamResolver.resolvedUrlFor(song.id)?.let { streamResolver.reject(song.id, it) }
        SourceDiagnostics.logReject(song.id, "duration", actual, song.durationMs, "re-resolve")

        val index = exoPlayer.currentMediaItemIndex
        exoPlayer.stop()
        exoPlayer.seekTo(index, 0L)
        exoPlayer.prepare()
        exoPlayer.play()
        return true
    }

    private fun handlePlayerError(error: PlaybackException) {
        autoSkipJob?.cancel()
        val song = _playerState.value.currentSong
        val resolveFailure = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<StreamResolveException>().firstOrNull()
        val index = exoPlayer.currentMediaItemIndex
        val wasPlaying = exoPlayer.playWhenReady

        if (resolveFailure == null && song != null && staleLinkRetriedFor != song.id) {
            // Not a resolve failure: most likely a CDN link that expired. Re-resolve once.
            staleLinkRetriedFor = song.id
            AppLogger.w(tag, "Stream failed for 《${song.title}》 (${error.errorCodeName}); re-resolving once")
            streamResolver.evict(song.id)
            exoPlayer.stop()
            exoPlayer.seekTo(index, 0L)
            exoPlayer.prepare()
            if (wasPlaying) exoPlayer.play()
            return
        }

        // Leave the error state; the queue is kept (never clearMediaItems here).
        exoPlayer.stop()
        val kind = resolveFailure?.kind ?: FailureKind.SOURCE_UNAVAILABLE
        val detail = resolveFailure?.detail ?: error.errorCodeName
        consecutiveFailures++
        AppLogger.w(tag, "Giving up on 《${song?.title}》 failure=${kind.name.lowercase().replace('_', '-')} code=${error.errorCodeName} ($detail)")
        _playerState.update {
            it.copy(
                isPlaying = false,
                isBuffering = false,
                playerError = failureMessage(it.currentSong?.title.orEmpty(), kind, detail),
                playbackFailure = PlaybackFailure(kind, detail)
            )
        }
        scheduleAutoSkip()
    }

    private fun scheduleAutoSkip() {
        val state = _playerState.value
        if (state.playlist.size <= 1 || exoPlayer.mediaItemCount <= 1) return
        // A dead network must not burn through the whole queue.
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            AppLogger.w(tag, "Stopping auto-skip after $consecutiveFailures consecutive failures")
            return
        }
        autoSkipJob = scope.launch {
            delay(AUTO_SKIP_DELAY_MS)
            val current = _playerState.value
            if (current.playerError != null && current.playlist.size > 1) skipNext()
        }
    }

    // ==================== Public playback API ====================

    fun playSong(song: Song, playlist: List<Song> = listOf(song)) {
        autoSkipJob?.cancel()
        lastPrefetchedSongId = null
        placeholderRejectedFor = null
        val index = playlist.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        val sameQueue = queueMatches(playlist)
        AppLogger.i(tag, "Preparing to play: 《${song.title}》 - ${song.artist} (${song.source}) queue=${playlist.size} sameQueue=$sameQueue")

        _playerState.update {
            it.copy(
                currentSong = song,
                playlist = playlist,
                currentIndex = index,
                currentPositionMs = 0L,
                durationMs = song.durationMs,
                isBuffering = true,
                playerError = null,
                playbackFailure = null
            )
        }

        try {
            applyPlaybackMode(_playerState.value.playbackMode)
            if (sameQueue) {
                exoPlayer.seekTo(index, 0L)
                if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
            } else {
                songsByUri.clear()
                exoPlayer.stop()
                exoPlayer.setMediaItems(playlist.map { toMediaItem(it) }, index, 0L)
                exoPlayer.prepare()
            }
            exoPlayer.play()
        } catch (e: IllegalStateException) {
            AppLogger.e(tag, "Failed to start playback of 《${song.title}》", e)
            _playerState.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    playerError = failureMessage(song.title, FailureKind.SOURCE_UNAVAILABLE, ""),
                    playbackFailure = PlaybackFailure(FailureKind.SOURCE_UNAVAILABLE, "")
                )
            }
        }
    }

    fun retryCurrent() {
        val state = _playerState.value
        val song = state.currentSong ?: return
        streamResolver.evict(song.id)
        consecutiveFailures = 0
        AppLogger.i(tag, "Retrying 《${song.title}》 on user request")
        playSong(song, state.playlist)
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
            return
        }
        val state = _playerState.value
        if (exoPlayer.playbackState == Player.STATE_IDLE && state.currentSong != null) {
            if (!queueMatches(state.playlist)) rebuildQueue()
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                val index = state.currentIndex.coerceIn(0, maxOf(0, exoPlayer.mediaItemCount - 1))
                exoPlayer.seekTo(index, 0L)
                exoPlayer.prepare()
            }
        }
        exoPlayer.play()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        _playerState.update { it.copy(currentPositionMs = positionMs) }
    }

    fun skipNext() {
        val state = _playerState.value
        if (state.playlist.isEmpty()) return
        autoSkipJob?.cancel()
        val from = state.currentIndex
        // In repeat-one, "next" is the following song (not this one again), as before.
        val next = if (exoPlayer.repeatMode == Player.REPEAT_MODE_ONE) {
            (from + 1) % state.playlist.size
        } else {
            exoPlayer.nextMediaItemIndex.takeIf { it >= 0 } ?: ((from + 1) % state.playlist.size)
        }
        playAtIndex(next)
    }

    fun skipPrevious() {
        val state = _playerState.value
        if (state.playlist.isEmpty()) return
        autoSkipJob?.cancel()
        if (exoPlayer.currentPosition > PREVIOUS_RESTART_MS) {
            seekTo(0L)
            return
        }
        val from = state.currentIndex
        val previous = if (exoPlayer.repeatMode == Player.REPEAT_MODE_ONE) {
            if (from <= 0) state.playlist.lastIndex else from - 1
        } else {
            exoPlayer.previousMediaItemIndex.takeIf { it >= 0 }
                ?: (if (from <= 0) state.playlist.lastIndex else from - 1)
        }
        playAtIndex(previous)
    }

    fun togglePlaybackMode() {
        val next = _playerState.value.playbackMode.next()
        _playerState.update { it.copy(playbackMode = next) }
        applyPlaybackMode(next)
        AppLogger.i(tag, "Playback mode: ${next.name}")
    }

    // ==================== Playlist Queue Management ====================

    fun playNext(song: Song) {
        val state = _playerState.value
        if (state.currentSong == null || state.playlist.isEmpty()) {
            playSong(song, listOf(song))
            return
        }

        val newPlaylist = state.playlist.toMutableList()
        newPlaylist.indexOfFirst { it.id == song.id }.takeIf { it != -1 }?.let { newPlaylist.removeAt(it) }
        val currentIdx = newPlaylist.indexOfFirst { it.id == state.currentSong.id }
        val insertPos = if (currentIdx != -1) currentIdx + 1 else newPlaylist.size
        newPlaylist.add(insertPos, song)
        commitPlaylist(newPlaylist)
        AppLogger.i(tag, "Queued 《${song.title}》 to play next")
    }

    fun addToPlaylist(song: Song) {
        val state = _playerState.value
        if (state.currentSong == null || state.playlist.isEmpty()) {
            playSong(song, listOf(song))
            return
        }
        if (state.playlist.any { it.id == song.id }) {
            AppLogger.d(tag, "《${song.title}》 already in playlist")
            return
        }
        commitPlaylist(state.playlist + song)
        AppLogger.i(tag, "Added 《${song.title}》 to end of playlist")
    }

    fun removeFromPlaylist(songId: String) {
        val state = _playerState.value
        val existingIndex = state.playlist.indexOfFirst { it.id == songId }
        if (existingIndex == -1) return

        if (songId != state.currentSong?.id) {
            commitPlaylist(state.playlist.toMutableList().apply { removeAt(existingIndex) })
            AppLogger.i(tag, "Removed song $songId from playlist")
            return
        }
        if (state.playlist.size <= 1) {
            stopAndClearQueue()
            AppLogger.i(tag, "Removed only song from playlist, playback stopped")
            return
        }

        val wasPlaying = state.isPlaying || exoPlayer.playWhenReady
        val newPlaylist = state.playlist.toMutableList().apply { removeAt(existingIndex) }
        val nextIndex = existingIndex.coerceAtMost(newPlaylist.lastIndex)
        commitPlaylist(newPlaylist)
        // The song that took the removed slot becomes current; make sure it actually starts.
        placeholderRejectedFor = null
        editQueue(newPlaylist.map { it.id }) {
            exoPlayer.seekTo(nextIndex, 0L)
            if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
            if (wasPlaying) exoPlayer.play()
        }
        AppLogger.i(tag, "Removed currently playing song $songId, advanced to 《${newPlaylist[nextIndex].title}》")
    }

    fun reorderPlaylist(fromIndex: Int, toIndex: Int) {
        val state = _playerState.value
        if (fromIndex !in state.playlist.indices || toIndex !in state.playlist.indices || fromIndex == toIndex) return

        val newPlaylist = state.playlist.toMutableList()
        val movedItem = newPlaylist.removeAt(fromIndex)
        newPlaylist.add(toIndex, movedItem)
        commitPlaylist(newPlaylist)
        AppLogger.d(tag, "Reordered playlist: moved from $fromIndex to $toIndex")
    }

    fun clearPlaylist() {
        stopAndClearQueue()
        AppLogger.i(tag, "Cleared playlist queue")
    }

    fun insertSongIntoPlaylist(index: Int, song: Song) {
        val state = _playerState.value
        if (state.playlist.isEmpty() && state.currentSong == null) {
            playSong(song, listOf(song))
            return
        }
        if (state.playlist.any { it.id == song.id }) return
        val newPlaylist = state.playlist.toMutableList()
        val safeIndex = index.coerceIn(0, newPlaylist.size)
        newPlaylist.add(safeIndex, song)
        commitPlaylist(newPlaylist)
        AppLogger.i(tag, "Restored 《${song.title}》 to playlist at index $safeIndex")
    }

    fun release() {
        stopProgressTracker()
        autoSkipJob?.cancel()
        exoPlayer.removeListener(playerListener)
        exoPlayer.release()
    }
}
