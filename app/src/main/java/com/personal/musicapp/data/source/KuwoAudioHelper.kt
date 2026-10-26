package com.personal.musicapp.data.source

import android.util.Log
import com.google.gson.JsonParser
import com.personal.musicapp.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object KuwoAudioHelper {

    private const val TAG = "KuwoAudioHelper"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun getPlayableUrl(songName: String, artist: String = ""): String? = withContext(Dispatchers.IO) {
        val query = if (artist.isNotEmpty() && artist != "群星" && artist != "未知歌曲") {
            "$songName $artist"
        } else {
            songName
        }.trim()

        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "http://search.kuwo.cn/r.s?all=$encoded&ft=music&itemset=web_2013&client=kt&pn=0&rn=3&rformat=json&encoding=utf8"

            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val songRid = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                // Kuwo returns single-quoted JSON-like string, standardise it
                val jsonStr = body.replace('\'', '"')
                val root = JsonParser.parseString(jsonStr).asJsonObject
                val abslist = root.getAsJsonArray("abslist")
                if (abslist != null && abslist.size() > 0) {
                    val first = abslist[0].asJsonObject
                    first.get("DC_TARGETID")?.asString
                        ?: first.get("MUSICRID")?.asString?.replace("MUSIC_", "")
                } else null
            } ?: return@withContext null

            // Get direct MP3 URL
            val convertUrl = "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=$songRid&format=mp3&response=url"
            val convertReq = Request.Builder().url(convertUrl).build()

            client.newCall(convertReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val streamUrl = resp.body?.string()?.trim()
                if (!streamUrl.isNullOrEmpty() && streamUrl.startsWith("http")) {
                    Log.d(TAG, "Successfully resolved Kuwo stream for '$query': $streamUrl")
                    return@withContext streamUrl
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kuwo fallback error for $query", e)
        }
        return@withContext null
    }

    suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Song>()
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val searchUrl = "http://search.kuwo.cn/r.s?all=$encoded&ft=music&itemset=web_2013&client=kt&pn=0&rn=15&rformat=json&encoding=utf8"

            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val jsonStr = body.replace('\'', '"')
                val root = JsonParser.parseString(jsonStr).asJsonObject
                val abslist = root.getAsJsonArray("abslist") ?: return@withContext emptyList()

                for (item in abslist) {
                    val obj = item.asJsonObject
                    val name = obj.get("SONGNAME")?.asString ?: continue
                    val artist = obj.get("ARTIST")?.asString ?: "群星"
                    val duration = (obj.get("DURATION")?.asLong ?: 0L) * 1000L
                    val rid = obj.get("DC_TARGETID")?.asString
                        ?: obj.get("MUSICRID")?.asString?.replace("MUSIC_", "")
                        ?: continue

                    var cover = ""
                    val webPic = obj.get("web_albumpic_short")?.asString
                    if (!webPic.isNullOrEmpty()) {
                        cover = "https://img4.kuwo.cn/star/albumcover/$webPic"
                    }

                    results.add(
                        Song(
                            id = "kw_$rid",
                            title = name,
                            artist = artist,
                            coverUrl = cover,
                            audioUrl = "kuwo://$rid",
                            durationMs = duration,
                            source = "酷我"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kuwo search error for $query", e)
        }
        return@withContext results
    }
}
