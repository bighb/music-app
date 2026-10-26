package com.personal.musicapp.data.source

import com.google.gson.JsonParser
import com.personal.musicapp.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class NetMusicAudioSource : AudioSource {

    override val sourceName: String = "全网音乐"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun canHandleLink(text: String): Boolean = false

    override suspend fun parseShareLink(text: String): Song? = null

    override suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<Song>()
        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val searchUrl = "https://music.163.com/api/search/get/web?s=$encodedQuery&type=1&offset=0&total=true&limit=30"

            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://music.163.com/")
                .build()

            val songIds = mutableListOf<Long>()
            val rawSongs = mutableListOf<Triple<Long, String, Pair<String, Long>>>() // id, name, (artist, duration)

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()

                val root = JsonParser.parseString(body).asJsonObject
                val resultObj = root.getAsJsonObject("result") ?: return@withContext emptyList()
                val songsArray = resultObj.getAsJsonArray("songs") ?: return@withContext emptyList()

                for (item in songsArray) {
                    val songObj = item.asJsonObject
                    val id = songObj.get("id")?.asLong ?: continue
                    val name = songObj.get("name")?.asString ?: "未知歌曲"
                    val duration = songObj.get("duration")?.asLong ?: 0L

                    var artistName = "群星"
                    val artistsArray = songObj.getAsJsonArray("artists")
                    if (artistsArray != null && artistsArray.size() > 0) {
                        val artistObj = artistsArray[0].asJsonObject
                        artistName = artistObj.get("name")?.asString ?: "群星"
                    }

                    songIds.add(id)
                    rawSongs.add(Triple(id, name, Pair(artistName, duration)))
                }
            }

            // Batch query covers from song detail API
            val coverMap = fetchCoversForSongIds(songIds)

            for ((id, name, artistAndDuration) in rawSongs) {
                val (artist, duration) = artistAndDuration
                val cover = coverMap[id] ?: ""
                val audioUrl = "https://music.163.com/song/media/outer/url?id=$id.mp3"

                results.add(
                    Song(
                        id = "net_$id",
                        title = name,
                        artist = artist,
                        coverUrl = cover,
                        audioUrl = audioUrl,
                        durationMs = duration,
                        source = sourceName
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext results
    }

    private fun fetchCoversForSongIds(songIds: List<Long>): Map<Long, String> {
        if (songIds.isEmpty()) return emptyMap()
        val covers = mutableMapOf<Long, String>()
        try {
            val idsParam = songIds.joinToString(prefix = "[", postfix = "]", separator = ",")
            val detailUrl = "https://music.163.com/api/song/detail/?ids=$idsParam"

            val req = Request.Builder()
                .url(detailUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", "https://music.163.com/")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return covers
                val body = resp.body?.string() ?: return covers
                val root = JsonParser.parseString(body).asJsonObject
                val songsArray = root.getAsJsonArray("songs") ?: return covers

                for (item in songsArray) {
                    val sObj = item.asJsonObject
                    val id = sObj.get("id")?.asLong ?: continue
                    val albumObj = sObj.getAsJsonObject("album")
                    val picUrl = albumObj?.get("picUrl")?.asString
                    if (!picUrl.isNullOrEmpty()) {
                        // Upgrade to https and resize to 400x400 for crisp native display
                        val securePicUrl = picUrl.replace("http://", "https://") + "?param=400y400"
                        covers[id] = securePicUrl
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return covers
    }
}
