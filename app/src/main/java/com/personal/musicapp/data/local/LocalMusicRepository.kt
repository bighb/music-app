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
    private val historyFile = File(context.filesDir, "play_history_v1.json")
    private val audioDir = File(context.filesDir, "audio").apply { if (!exists()) mkdirs() }
    private val coverDir = File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }

    private val _favorites = MutableStateFlow<List<Song>>(emptyList())
    val favorites: StateFlow<List<Song>> = _favorites.asStateFlow()

    private val _playHistory = MutableStateFlow<List<Song>>(emptyList())
    val playHistory: StateFlow<List<Song>> = _playHistory.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    init {
        loadFavorites()
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

        val favoritedSong = song.copy(isFavorite = true, addedTimestamp = System.currentTimeMillis())
        current.add(0, favoritedSong)
        _favorites.value = current
        saveFavoritesToFile(current)
        AppLogger.i(tag, "Added favorite: 《${song.title}》")

        // Trigger background download for offline caching
        scope.launch {
            cacheSongLocally(favoritedSong)
        }
    }

    suspend fun removeFavorite(songId: String) = withContext(Dispatchers.IO) {
        val current = _favorites.value.toMutableList()
        val target = current.find { it.id == songId }
        current.removeAll { it.id == songId }
        _favorites.value = current
        saveFavoritesToFile(current)
        AppLogger.i(tag, "Removed favorite: ${target?.title ?: songId}")

        // Clean up cached files
        target?.let { deleteCachedFiles(it) }
    }

    private suspend fun cacheSongLocally(song: Song) = withContext(Dispatchers.IO) {
        try {
            var updatedSong = song
            // 1. Download audio file ONLY if not already downloaded or file is invalid
            val currentAudioPath = song.localAudioPath
            val isAudioCached = currentAudioPath != null && File(currentAudioPath).exists() && File(currentAudioPath).length() >= 10000

            if (!isAudioCached) {
                AppLogger.d(tag, "Resolving stream url to cache 《${song.title}》...")
                val directAudioUrl = resolveDownloadUrl(song)
                if (!directAudioUrl.isNullOrEmpty() && directAudioUrl.startsWith("http")) {
                    val audioFile = File(audioDir, "${cleanFileName(song.id)}.mp3")
                    val downloadSuccess = downloadFile(directAudioUrl, audioFile)
                    if (downloadSuccess && audioFile.exists() && audioFile.length() >= 10000) {
                        updatedSong = updatedSong.copy(localAudioPath = audioFile.absolutePath)
                        AppLogger.i(tag, "Cached audio successfully for 《${song.title}》 (${audioFile.length() / 1024} KB)")
                    } else {
                        AppLogger.w(tag, "Failed to download audio or file too small for 《${song.title}》: size=${audioFile.length()}")
                        if (audioFile.exists()) audioFile.delete()
                    }
                } else {
                    AppLogger.w(tag, "Could not resolve playable download URL for 《${song.title}》")
                }
            }

            // 2. Download cover file
            val isCoverCached = !song.localCoverPath.isNullOrEmpty() && File(song.localCoverPath).exists()
            if (!song.coverUrl.isNullOrEmpty() && !isCoverCached) {
                val coverFile = File(coverDir, "${cleanFileName(song.id)}.jpg")
                val downloadSuccess = downloadFile(song.coverUrl, coverFile)
                if (downloadSuccess && coverFile.exists() && coverFile.length() > 500) {
                    updatedSong = updatedSong.copy(localCoverPath = coverFile.absolutePath)
                }
            }

            // Update in favorites list
            val current = _favorites.value.toMutableList()
            val index = current.indexOfFirst { it.id == song.id }
            if (index != -1) {
                current[index] = updatedSong
                _favorites.value = current
                saveFavoritesToFile(current)
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Exception during cacheSongLocally for 《${song.title}》", e)
        }
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
