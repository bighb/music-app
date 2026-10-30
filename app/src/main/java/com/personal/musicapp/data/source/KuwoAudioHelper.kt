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
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    fun isKuwoPromoAudio(url: String): Boolean {
        // Kuwo replaces VIP/digital album songs with an 11-second promo audio (588957081.mp3) saying "去酷我最新app听"
        return url.contains("588957081.mp3")
    }

    private fun cleanHtmlEntities(str: String): String {
        return str
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    suspend fun getPlayableUrl(songName: String, artist: String = ""): String? = withContext(Dispatchers.IO) {
        val cleanArtist = if (artist.isNotEmpty() && artist != "群星" && artist != "未知歌曲") {
            artist.split("/", "、", ",", "&").first().trim()
        } else ""

        val rawTitle = cleanHtmlEntities(songName)
        val queries = mutableListOf<String>()

        if (cleanArtist.isNotEmpty()) {
            queries.add("$rawTitle $cleanArtist")
        }
        queries.add(rawTitle)

        // Also add stripped title without parentheses: "晴天 (Live)" -> "晴天"
        val strippedTitle = rawTitle.replace(Regex("[\\(\\[【（].*?[\\)\\]】）]"), "").trim()
        if (strippedTitle.isNotEmpty() && strippedTitle != rawTitle) {
            if (cleanArtist.isNotEmpty()) {
                queries.add("$strippedTitle $cleanArtist")
            }
            queries.add(strippedTitle)
        }

        for (q in queries) {
            val url = queryKuwoStream(q)
            if (!url.isNullOrEmpty()) {
                return@withContext url
            }
        }

        return@withContext null
    }

    private fun queryKuwoStream(query: String): String? {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val searchUrl = "http://search.kuwo.cn/r.s?all=$encoded&ft=music&itemset=web_2013&client=kt&pn=0&rn=5&rformat=json&encoding=utf8"

            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val rids = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<String>()
                val body = resp.body?.string() ?: return@use emptyList<String>()
                val jsonStr = body.replace('\'', '"')
                val root = JsonParser.parseString(jsonStr).asJsonObject
                val abslist = root.getAsJsonArray("abslist") ?: return@use emptyList<String>()

                val idList = mutableListOf<String>()
                for (i in 0 until minOf(3, abslist.size())) {
                    val item = abslist[i].asJsonObject
                    val rid = item.get("DC_TARGETID")?.asString
                        ?: item.get("MUSICRID")?.asString?.replace("MUSIC_", "")
                    if (!rid.isNullOrEmpty()) {
                        idList.add(rid)
                    }
                }
                idList
            }

            for (songRid in rids) {
                val convertUrl = "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=$songRid&format=mp3&response=url"
                val convertReq = Request.Builder().url(convertUrl).build()

                client.newCall(convertReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val streamUrl = resp.body?.string()?.trim()
                        if (!streamUrl.isNullOrEmpty() && streamUrl.startsWith("http")) {
                            if (isKuwoPromoAudio(streamUrl)) {
                                Log.w(TAG, "Kuwo stream for '$query' (rid=$songRid) is copyright promo audio, skipping")
                            } else {
                                Log.d(TAG, "Successfully resolved Kuwo stream for '$query' (rid=$songRid): $streamUrl")
                                return streamUrl
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kuwo query error for $query", e)
        }
        return null
    }

    suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Song>()
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val searchUrl = "http://search.kuwo.cn/r.s?all=$encoded&ft=music&itemset=web_2013&client=kt&pn=0&rn=20&rformat=json&encoding=utf8"

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
                    val rawName = obj.get("SONGNAME")?.asString ?: continue
                    val rawArtist = obj.get("ARTIST")?.asString ?: "群星"
                    val duration = (obj.get("DURATION")?.asLong ?: 0L) * 1000L
                    val rid = obj.get("DC_TARGETID")?.asString
                        ?: obj.get("MUSICRID")?.asString?.replace("MUSIC_", "")
                        ?: continue

                    val name = cleanHtmlEntities(rawName)
                    val artist = cleanHtmlEntities(rawArtist)

                    var cover = ""
                    val webPic = obj.get("web_albumpic_short")?.asString
                    val artistPic = obj.get("web_artistpic_short")?.asString
                    if (!webPic.isNullOrEmpty()) {
                        cover = "https://img4.kuwo.cn/star/albumcover/$webPic"
                    } else if (!artistPic.isNullOrEmpty()) {
                        cover = "https://img4.kuwo.cn/star/starheads/$artistPic"
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
