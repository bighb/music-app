package com.personal.musicapp.data.source

import com.google.gson.JsonParser
import com.personal.musicapp.data.model.SearchSuggestion
import com.personal.musicapp.util.AppLogger
import com.personal.musicapp.util.SearchInputUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SearchSuggestHelper(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()
) {
    private val tag = "SearchSuggestHelper"

    /**
     * Fetches search recommendations mixing local history and fast online suggestions.
     * Returns empty list immediately if input is a share link/content.
     */
    suspend fun getSuggestions(
        query: String,
        localHistory: List<String> = emptyList()
    ): List<SearchSuggestion> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || SearchInputUtils.isShareContentOrUrl(trimmed)) {
            return@withContext emptyList()
        }

        // 1. Match local search history (fuzzy/prefix, up to 3)
        val historyMatches = localHistory
            .filter { it.contains(trimmed, ignoreCase = true) && !it.equals(trimmed, ignoreCase = true) }
            .take(3)
            .map { SearchSuggestion(keyword = it, subtitle = "历史记录", isHistory = true, source = "历史") }

        // 2. Fetch online smart suggestions (QQ Music smartbox - instant domestic CDN)
        val onlineMatches = mutableListOf<SearchSuggestion>()
        try {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=$encoded&format=json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", "https://y.qq.com/")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val root = JsonParser.parseString(body).asJsonObject
                val data = root.getAsJsonObject("data")
                if (data != null) {
                    // Match Songs
                    val songObj = data.getAsJsonObject("song")
                    val songItems = songObj?.getAsJsonArray("itemlist")
                    if (songItems != null) {
                        for (i in 0 until minOf(songItems.size(), 4)) {
                            val item = songItems[i].asJsonObject
                            val name = item.get("name")?.asString ?: ""
                            val singer = item.get("singer")?.asString ?: ""
                            if (name.isNotEmpty()) {
                                onlineMatches.add(
                                    SearchSuggestion(
                                        keyword = if (singer.isNotEmpty()) "$name $singer" else name,
                                        subtitle = if (singer.isNotEmpty()) "单曲 · $singer" else "单曲",
                                        isHistory = false,
                                        source = "QQ"
                                    )
                                )
                            }
                        }
                    }

                    // Match Singers
                    val singerObj = data.getAsJsonObject("singer")
                    val singerItems = singerObj?.getAsJsonArray("itemlist")
                    if (singerItems != null) {
                        for (i in 0 until minOf(singerItems.size(), 2)) {
                            val item = singerItems[i].asJsonObject
                            val name = item.get("name")?.asString ?: ""
                            if (name.isNotEmpty()) {
                                onlineMatches.add(
                                    SearchSuggestion(
                                        keyword = name,
                                        subtitle = "歌手",
                                        isHistory = false,
                                        source = "QQ"
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.d(tag, "Online suggest silent error: ${e.message}")
        }

        // 3. Deduplicate and take top 6 items
        val combined = mutableListOf<SearchSuggestion>()
        val seen = mutableSetOf<String>()

        for (item in historyMatches) {
            if (seen.add(item.keyword.lowercase())) {
                combined.add(item)
            }
        }
        for (item in onlineMatches) {
            if (seen.add(item.keyword.lowercase())) {
                combined.add(item)
            }
        }

        combined.take(6)
    }
}
