package com.personal.musicapp.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.personal.musicapp.data.model.Song
import com.personal.musicapp.data.source.KuwoAudioHelper
import com.personal.musicapp.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class DownloadState {
    object NotDownloaded : DownloadState()
    data class Downloading(val progress: Float = 0f) : DownloadState()
    object Downloaded : DownloadState()
    data class Failed(val error: String) : DownloadState()
}

class LocalMusicRepository(private val context: Context) {

    private val tag = "LocalMusicRepository"
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences("music_app_prefs", Context.MODE_PRIVATE)
    private val favoritesFile = File(context.filesDir, "favorites_v1.json")
    private val downloadsFile = File(context.filesDir, "downloads_v1.json")
    private val historyFile = File(context.filesDir, "play_history_v1.json")
    private val audioDir = File(context.filesDir, "audio").apply { if (!exists()) mkdirs() }
    private val coverDir = File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }

    private val _favorites = MutableStateFlow<List<Song>>(emptyList())
    val favorites: StateFlow<List<Song>> = _favorites.asStateFlow()

    private val _downloads = MutableStateFlow<List<Song>>(emptyList())
    val downloads: StateFlow<List<Song>> = _downloads.asStateFlow()

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    private val _playHistory = MutableStateFlow<List<Song>>(emptyList())
    val playHistory: StateFlow<List<Song>> = _playHistory.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    init {
        loadFavorites()
        loadDownloads()
        loadPlayHistory()
        loadSearchHistory()
    }

    // ==================== Favorites ====================

    private fun loadFavorites() {
        if (!favoritesFile.exists()) return
        try {
            val json = favoritesFile.readText()
            val type = object : TypeToken<List<Song>>() {}.type
            val list: List<Song> = gson.fromJson(json, type) ?: emptyList()
            // Validate local files exist and are not corrupted (>= 10KB)
            val validated = list.map { song ->
                var audioPath = song.localAudioPath
                if (audioPath != null) {
                    val f = File(audioPath)
                    if (!f.exists() || f.length() < 10000) {
                        if (f.exists()) f.delete()
                        audioPath = null
                    }
                }
                song.copy(localAudioPath = audioPath)
            }
            _favorites.value = validated
            AppLogger.d(tag, "Loaded ${validated.size} favorites (${validated.count { it.localAudioPath != null }} cached)")
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to load favorites", e)
        }
    }

    private fun saveFavoritesToFile(list: List<Song>) {
        try {
            val json = gson.toJson(list)
            favoritesFile.writeText(json)
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to save favorites", e)
        }
    }

    fun isFavorite(songId: String): Boolean {
        return _favorites.value.any { it.id == songId }
    }

    suspend fun toggleFavorite(song: Song) = withContext(Dispatchers.IO) {
        if (isFavorite(song.id)) {
            removeFavorite(song.id)
        } else {
            addFavorite(song)
        }
    }

    suspend fun addFavorite(song: Song) = withContext(Dispatchers.IO) {
        val current = _favorites.value.toMutableList()
        current.removeAll { it.id == song.id }

        val existingDownload = _downloads.value.find { it.id == song.id }
        val favoritedSong = song.copy(
            isFavorite = true,
            localAudioPath = existingDownload?.localAudioPath ?: song.localAudioPath,
            localCoverPath = existingDownload?.localCoverPath ?: song.localCoverPath,
            addedTimestamp = System.currentTimeMillis()
        )
        current.add(0, favoritedSong)
        _favorites.value = current
        saveFavoritesToFile(current)
        AppLogger.i(tag, "Added favorite: 《${song.title}》")
    }

    suspend fun removeFavorite(songId: String) = withContext(Dispatchers.IO) {
        val current = _favorites.value.toMutableList()
        val target = current.find { it.id == songId }
        current.removeAll { it.id == songId }
        _favorites.value = current
        saveFavoritesToFile(current)
        AppLogger.i(tag, "Removed favorite: ${target?.title ?: songId}")
    }

    // ==================== Downloads ====================

    private fun loadDownloads() {
        if (!downloadsFile.exists()) {
            val initialDownloads = _favorites.value.filter { it.localAudioPath != null && File(it.localAudioPath).exists() }
            if (initialDownloads.isNotEmpty()) {
                _downloads.value = initialDownloads
                saveDownloadsToFile(initialDownloads)
                updateDownloadStates(initialDownloads)
                AppLogger.d(tag, "Migrated ${initialDownloads.size} downloads from favorites")
            }
            return
        }
        try {
            val json = downloadsFile.readText()
            val type = object : TypeToken<List<Song>>() {}.type
            val list: List<Song> = gson.fromJson(json, type) ?: emptyList()
            val validated = list.mapNotNull { song ->
                val audioPath = song.localAudioPath
                if (audioPath != null) {
                    val f = File(audioPath)
                    if (f.exists() && f.length() >= 10000) {
                        return@mapNotNull song
                    } else {
                        if (f.exists()) f.delete()
                    }
                }
                null
            }
            _downloads.value = validated
            updateDownloadStates(validated)
            AppLogger.d(tag, "Loaded ${validated.size} downloads")
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to load downloads", e)
        }
    }

    private fun updateDownloadStates(downloadedSongs: List<Song>) {
        val states = _downloadStates.value.toMutableMap()
        for (song in downloadedSongs) {
            states[song.id] = DownloadState.Downloaded
        }
        _downloadStates.value = states
    }

    private fun saveDownloadsToFile(list: List<Song>) {
        try {
            val json = gson.toJson(list)
            downloadsFile.writeText(json)
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to save downloads", e)
        }
    }

    fun isDownloaded(songId: String): Boolean {
        return _downloads.value.any { it.id == songId && it.localAudioPath != null && File(it.localAudioPath).exists() }
    }

    fun getDownloadState(songId: String): DownloadState {
        val state = _downloadStates.value[songId]
        if (state != null) return state
        return if (isDownloaded(songId)) DownloadState.Downloaded else DownloadState.NotDownloaded
    }

    fun downloadSong(song: Song, onFinished: ((Boolean, String?) -> Unit)? = null) {
        if (isDownloaded(song.id)) {
            onFinished?.invoke(true, null)
            return
        }

        val currentStates = _downloadStates.value.toMutableMap()
        currentStates[song.id] = DownloadState.Downloading(0f)
        _downloadStates.value = currentStates

        scope.launch {
            try {
                AppLogger.d(tag, "Starting manual download for 《${song.title}》...")
                val directAudioUrl = resolveDownloadUrl(song)
                if (directAudioUrl.isNullOrEmpty() || !directAudioUrl.startsWith("http")) {
                    val errMsg = "未能解析到有效下载直链"
                    AppLogger.w(tag, "Could not resolve playable download URL for 《${song.title}》")
                    val failedStates = _downloadStates.value.toMutableMap()
                    failedStates[song.id] = DownloadState.Failed(errMsg)
                    _downloadStates.value = failedStates
                    onFinished?.invoke(false, errMsg)
                    return@launch
                }

                val audioFile = File(audioDir, "${cleanFileName(song.id)}.mp3")
                val downloadSuccess = downloadFile(directAudioUrl, audioFile)
                if (!downloadSuccess || !audioFile.exists() || audioFile.length() < 10000) {
                    if (audioFile.exists()) audioFile.delete()
                    val errMsg = "音频下载失败或文件损坏"
                    val failedStates = _downloadStates.value.toMutableMap()
                    failedStates[song.id] = DownloadState.Failed(errMsg)
                    _downloadStates.value = failedStates
                    onFinished?.invoke(false, errMsg)
                    return@launch
                }

                var updatedSong = song.copy(localAudioPath = audioFile.absolutePath)

                if (!song.coverUrl.isNullOrEmpty()) {
                    val coverFile = File(coverDir, "${cleanFileName(song.id)}.jpg")
                    val coverSuccess = downloadFile(song.coverUrl, coverFile)
                    if (coverSuccess && coverFile.exists() && coverFile.length() > 500) {
                        updatedSong = updatedSong.copy(localCoverPath = coverFile.absolutePath)
                    }
                }

                val currentDownloads = _downloads.value.toMutableList()
                currentDownloads.removeAll { it.id == song.id }
                currentDownloads.add(0, updatedSong)
                _downloads.value = currentDownloads
                saveDownloadsToFile(currentDownloads)

                val currentFavorites = _favorites.value.toMutableList()
                val favIndex = currentFavorites.indexOfFirst { it.id == song.id }
                if (favIndex != -1) {
                    currentFavorites[favIndex] = currentFavorites[favIndex].copy(
                        localAudioPath = updatedSong.localAudioPath,
                        localCoverPath = updatedSong.localCoverPath
                    )
                    _favorites.value = currentFavorites
                    saveFavoritesToFile(currentFavorites)
                }

                val doneStates = _downloadStates.value.toMutableMap()
                doneStates[song.id] = DownloadState.Downloaded
                _downloadStates.value = doneStates
                AppLogger.i(tag, "Downloaded 《${song.title}》 successfully (${audioFile.length() / 1024} KB)")
                onFinished?.invoke(true, null)
            } catch (e: Exception) {
                AppLogger.e(tag, "Download failed for 《${song.title}》", e)
                val failedStates = _downloadStates.value.toMutableMap()
                failedStates[song.id] = DownloadState.Failed(e.localizedMessage ?: "下载异常")
                _downloadStates.value = failedStates
                onFinished?.invoke(false, e.localizedMessage ?: "网络异常")
            }
        }
    }

    suspend fun deleteDownload(songId: String) = withContext(Dispatchers.IO) {
        val currentDownloads = _downloads.value.toMutableList()
        val target = currentDownloads.find { it.id == songId }
        currentDownloads.removeAll { it.id == songId }
        _downloads.value = currentDownloads
        saveDownloadsToFile(currentDownloads)

        target?.let { deleteCachedFiles(it) }

        val currentFavorites = _favorites.value.toMutableList()
        val favIndex = currentFavorites.indexOfFirst { it.id == songId }
        if (favIndex != -1) {
            currentFavorites[favIndex] = currentFavorites[favIndex].copy(
                localAudioPath = null,
                localCoverPath = null
            )
            _favorites.value = currentFavorites
            saveFavoritesToFile(currentFavorites)
        }

        val states = _downloadStates.value.toMutableMap()
        states[songId] = DownloadState.NotDownloaded
        _downloadStates.value = states
        AppLogger.i(tag, "Deleted download: ${target?.title ?: songId}")
    }

    private suspend fun resolveDownloadUrl(song: Song): String? {
        val raw = song.audioUrl
        if (raw.startsWith("kuwo://")) {
            val rid = raw.removePrefix("kuwo://")
            val convertUrl = "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=$rid&format=mp3&response=url"
            try {
                val req = Request.Builder().url(convertUrl).build()
                client.newCall(req).execute().use { resp ->
                    val streamUrl = resp.body?.string()?.trim()
                    if (!streamUrl.isNullOrEmpty() && streamUrl.startsWith("http")) {
                        return streamUrl
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "Kuwo convert_url failed for $rid", e)
            }
            return KuwoAudioHelper.getPlayableUrl(song.title, song.artist)
        }

        if (raw.contains("music.163.com")) {
            // First try resolving 163 outer url redirect
            try {
                val noRedirectClient = OkHttpClient.Builder().followRedirects(false).build()
                val req = Request.Builder().url(raw).build()
                noRedirectClient.newCall(req).execute().use { resp ->
                    val loc = resp.header("Location")
                    if (!loc.isNullOrEmpty() && !loc.contains("/404")) {
                        return loc.replace("http://", "https://")
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(tag, "Netease redirect check failed: ${e.message}")
            }

            // Fallback to Kuwo
            val fallback = KuwoAudioHelper.getPlayableUrl(song.title, song.artist)
            if (!fallback.isNullOrEmpty()) return fallback
        }

        return raw
    }

    private fun downloadFile(url: String, destFile: File): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.w(tag, "Download failed HTTP code ${response.code} for: $url")
                    return false
                }
                val body = response.body ?: return false
                val contentType = response.header("Content-Type") ?: ""
                if (contentType.contains("text/html")) {
                    AppLogger.w(tag, "Download returned HTML instead of audio: $url")
                    return false
                }

                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { fos ->
                    body.byteStream().copyTo(fos)
                }
                true
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Download exception for: $url", e)
            if (destFile.exists()) destFile.delete()
            false
        }
    }

    private fun deleteCachedFiles(song: Song) {
        try {
            song.localAudioPath?.let { path ->
                val f = File(path)
                if (f.exists()) f.delete()
            }
            song.localCoverPath?.let { path ->
                val f = File(path)
                if (f.exists()) f.delete()
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to delete cached files", e)
        }
    }

    private fun cleanFileName(id: String): String {
        return id.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
    }

    // ==================== Play History ====================

    private fun loadPlayHistory() {
        if (!historyFile.exists()) return
        try {
            val json = historyFile.readText()
            val type = object : TypeToken<List<Song>>() {}.type
            val list: List<Song> = gson.fromJson(json, type) ?: emptyList()
            _playHistory.value = list
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to load play history", e)
        }
    }

    private fun savePlayHistoryToFile(list: List<Song>) {
        try {
            val json = gson.toJson(list)
            historyFile.writeText(json)
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to save play history", e)
        }
    }

    fun recordPlayback(song: Song) {
        scope.launch {
            val current = _playHistory.value.toMutableList()
            current.removeAll { it.id == song.id }
            current.add(0, song.copy(addedTimestamp = System.currentTimeMillis()))
            // Keep at most 100 history items
            if (current.size > 100) {
                current.removeAt(current.lastIndex)
            }
            _playHistory.value = current
            savePlayHistoryToFile(current)
            AppLogger.d(tag, "Recorded playback history: 《${song.title}》")
        }
    }

    fun removePlayHistoryItem(songId: String) {
        scope.launch {
            val current = _playHistory.value.toMutableList()
            current.removeAll { it.id == songId }
            _playHistory.value = current
            savePlayHistoryToFile(current)
        }
    }

    fun clearPlayHistory() {
        scope.launch {
            _playHistory.value = emptyList()
            if (historyFile.exists()) historyFile.delete()
            AppLogger.i(tag, "Cleared play history")
        }
    }

    // ==================== Search History ====================

    private fun loadSearchHistory() {
        try {
            val historyStr = prefs.getString("search_history_v1", "") ?: ""
            if (historyStr.isNotEmpty()) {
                val list = historyStr.split("|||").filter { it.isNotBlank() }
                _searchHistory.value = list
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to load search history", e)
        }
    }

    fun addSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val current = _searchHistory.value.toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        if (current.size > 15) {
            current.removeAt(current.lastIndex)
        }
        _searchHistory.value = current
        prefs.edit().putString("search_history_v1", current.joinToString("|||")).apply()
    }

    fun removeSearchQuery(query: String) {
        val current = _searchHistory.value.toMutableList()
        current.removeAll { it.equals(query, ignoreCase = true) }
        _searchHistory.value = current
        prefs.edit().putString("search_history_v1", current.joinToString("|||")).apply()
    }

    fun clearSearchHistory() {
        _searchHistory.value = emptyList()
        prefs.edit().remove("search_history_v1").apply()
    }
}
