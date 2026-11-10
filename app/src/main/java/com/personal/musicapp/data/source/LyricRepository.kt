package com.personal.musicapp.data.source

import android.content.Context
import com.google.gson.JsonParser
import com.personal.musicapp.data.model.LyricEntry
import com.personal.musicapp.data.model.Song
import com.personal.musicapp.util.AppLogger
import com.personal.musicapp.util.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LyricRepository(private val context: Context) {

    private val tag = "LyricRepository"
    private val memCache = ConcurrentHashMap<String, List<LyricEntry>>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val lyricDir: File by lazy {
        File(context.filesDir, "lyrics").apply {
            if (!exists()) mkdirs()
        }
    }

    suspend fun getLyrics(song: Song): List<LyricEntry> = withContext(Dispatchers.IO) {
        // 1. In-memory cache
        memCache[song.id]?.let { return@withContext it }

        // 2. Disk cache
        val diskFile = File(lyricDir, "${song.id}.lrc")
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val content = diskFile.readText()
                val parsed = LrcParser.parse(content)
                if (parsed.isNotEmpty()) {
                    memCache[song.id] = parsed
                    return@withContext parsed
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "Failed reading cached lyric for ${song.title}", e)
            }
        }

        // 3. Online fetch from NetEase
        val lrcText = fetchOnlineLyric(song)
        if (!lrcText.isNullOrBlank()) {
            val parsed = LrcParser.parse(lrcText)
            if (parsed.isNotEmpty()) {
                memCache[song.id] = parsed
                try {
                    diskFile.writeText(lrcText)
                } catch (e: Exception) {
                    AppLogger.w(tag, "Failed writing lyric to disk for ${song.title}", e)
                }
                return@withContext parsed
            }
        }

        return@withContext emptyList()
    }

    private fun fetchOnlineLyric(song: Song): String? {
        var neteaseId: Long? = null
        if (song.id.startsWith("net_")) {
            neteaseId = song.id.removePrefix("net_").toLongOrNull()
        }

        // If not a direct netease song ID, search NetEase to find the matched song ID
        if (neteaseId == null) {
            val cleanTitle = song.title.replace(Regex("[\\(\\[【（].*?[\\)\\]】）]"), "").trim()
            val query = "$cleanTitle ${song.artist}".trim()
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "https://music.163.com/api/search/get/web?s=$encoded&type=1&offset=0&total=true&limit=1"
                val searchReq = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://music.163.com/")
                    .build()

                client.newCall(searchReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val root = JsonParser.parseString(body).asJsonObject
                        val songs = root.getAsJsonObject("result")?.getAsJsonArray("songs")
                        if (songs != null && songs.size() > 0) {
                            neteaseId = songs[0].asJsonObject.get("id")?.asLong
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(tag, "Search netease ID for lyric error: $query", e)
            }
        }

        if (neteaseId == null) return null

        try {
            val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$neteaseId&lv=-1&kv=-1&tv=-1"
            val lyricReq = Request.Builder()
                .url(lyricUrl)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://music.163.com/")
                .build()

            client.newCall(lyricReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val root = JsonParser.parseString(body).asJsonObject
                    val lrcObj = root.getAsJsonObject("lrc")
                    return lrcObj?.get("lyric")?.asString
                }
            }
        } catch (e: Exception) {
            AppLogger.w(tag, "Fetch lyric error for neteaseId: $neteaseId", e)
        }

        return null
    }
}
