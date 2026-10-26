package com.personal.musicapp.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.personal.musicapp.data.model.Song
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

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val favoritesFile = File(context.filesDir, "favorites_v1.json")
    private val audioDir = File(context.filesDir, "audio").apply { if (!exists()) mkdirs() }
    private val coverDir = File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }

    private val _favorites = MutableStateFlow<List<Song>>(emptyList())
    val favorites: StateFlow<List<Song>> = _favorites.asStateFlow()

    init {
        loadFavorites()
    }

    private fun loadFavorites() {
        if (!favoritesFile.exists()) return
        try {
            val json = favoritesFile.readText()
            val type = object : TypeToken<List<Song>>() {}.type
            val list: List<Song> = gson.fromJson(json, type) ?: emptyList()
            _favorites.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveFavoritesToFile(list: List<Song>) {
        try {
            val json = gson.toJson(list)
            favoritesFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
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

        // Clean up cached files
        target?.let { deleteCachedFiles(it) }
    }

    private suspend fun cacheSongLocally(song: Song) = withContext(Dispatchers.IO) {
        try {
            var updatedSong = song
            // 1. Download audio file
            if (song.localAudioPath == null || !File(song.localAudioPath).exists()) {
                val audioFile = File(audioDir, "${cleanFileName(song.id)}.mp3")
                val downloadSuccess = downloadFile(song.audioUrl, audioFile)
                if (downloadSuccess) {
                    updatedSong = updatedSong.copy(localAudioPath = audioFile.absolutePath)
                }
            }

            // 2. Download cover file
            if (!song.coverUrl.isNullOrEmpty() && (song.localCoverPath == null || !File(song.localCoverPath).exists())) {
                val coverFile = File(coverDir, "${cleanFileName(song.id)}.jpg")
                val downloadSuccess = downloadFile(song.coverUrl, coverFile)
                if (downloadSuccess) {
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
            e.printStackTrace()
        }
    }

    private fun downloadFile(url: String, destFile: File): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                FileOutputStream(destFile).use { fos ->
                    body.byteStream().copyTo(fos)
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
            e.printStackTrace()
        }
    }

    private fun cleanFileName(id: String): String {
        return id.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
    }
}
