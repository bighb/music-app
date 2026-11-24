package com.personal.musicapp.data.source

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personal.musicapp.data.model.Song
import com.personal.musicapp.util.AppLogger
import com.personal.musicapp.util.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

/**
 * Free-tier Kuwo catalogue access. `kuwo://<rid>` placeholders produced here are decoded
 * by [StreamResolver], never here.
 */
object KuwoAudioHelper {

    private const val TAG = "Kuwo"
    private const val MAX_TITLE_QUERIES = 2
    private const val MAX_TITLE_RIDS = 2
    private const val SEARCH_RN = 20

    private val searchClient by lazy { HttpPolicy.searchClient(HttpPolicy.KUWO) }
    private val convertClient by lazy { HttpPolicy.resolveClient() }

    private const val SEARCH_URL =
        "http://search.kuwo.cn/r.s?all=%s&ft=music&itemset=web_2013&client=kt&pn=0&rn=%d&rformat=json&encoding=utf8"

    fun isKuwoPromoAudio(url: String): Boolean = PlaceholderGate.isPromo(url)

    fun antiServerUrl(rid: String, format: String = "mp3"): String =
        "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=$rid&format=$format&response=url"

    /**
     * Kuwo's r.s replies with single-quoted pseudo-JSON. Only quotes that delimit a value
     * are structural; an apostrophe inside a title is content and must survive.
     */
    internal fun normalizeKuwoJson(body: String): String {
        if (body.indexOf('\'') < 0) return body
        val out = StringBuilder(body.length + 16)
        var i = 0
        val n = body.length
        while (i < n) {
            val c = body[i]
            if (c != '\'') {
                out.append(c)
                i++
                continue
            }
            out.append('"')
            i++
            var closed = false
            while (i < n && !closed) {
                val d = body[i]
                when {
                    d == '\\' -> {
                        if (i + 1 < n) {
                            out.append(d).append(body[i + 1])
                            i += 2
                        } else {
                            out.append(d)
                            i++
                        }
                    }
                    d == '"' -> {
                        out.append("\\\"")
                        i++
                    }
                    d == '\'' -> {
                        var j = i + 1
                        while (j < n && body[j].isWhitespace()) j++
                        if (j >= n || body[j] in ":,}]") {
                            out.append('"')
                            closed = true
                        } else {
                            out.append('\'')
                        }
                        i++
                    }
                    else -> {
                        out.append(d)
                        i++
                    }
                }
            }
        }
        return out.toString()
    }

    private fun str(obj: JsonObject, key: String): String? {
        val el: JsonElement = obj.get(key) ?: return null
        return if (el.isJsonPrimitive) el.asString else null
    }

    private fun ridOf(obj: JsonObject): String? =
        str(obj, "DC_TARGETID") ?: str(obj, "MUSICRID")?.removePrefix("MUSIC_")

    internal fun cleanHtmlEntities(str: String): String {
        return str
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    /** Title queries tried when a song's own id yields nothing. At most [MAX_TITLE_QUERIES]. */
    internal fun titleQueries(songName: String, artist: String): List<String> {
        val cleanArtist = if (artist.isNotEmpty() && artist != "群星" && artist != "未知歌曲") {
            artist.split("/", "、", ",", "&").first().trim()
        } else ""

        val rawTitle = cleanHtmlEntities(songName)
        val strippedTitle = rawTitle.replace(Regex("[\\(\\[【（].*?[\\)\\]】）]"), "").trim()

        val queries = mutableListOf<String>()
        if (cleanArtist.isNotEmpty()) queries.add("$rawTitle $cleanArtist")
        if (strippedTitle.isNotEmpty() && strippedTitle != rawTitle) queries.add(strippedTitle)
        queries.add(rawTitle)
        return queries.distinct().take(MAX_TITLE_QUERIES)
    }

    suspend fun getPlayableUrl(songName: String, artist: String = ""): String? = withContext(Dispatchers.IO) {
        for (q in titleQueries(songName, artist)) {
            val url = queryKuwoStream(q) ?: continue
            return@withContext url
        }
        null
    }

    private suspend fun queryKuwoStream(query: String): String? {
        try {
            val rids = searchRids(query, MAX_TITLE_RIDS) ?: return null
            for (songRid in rids) {
                val streamUrl = convertRid(songRid) ?: continue
                return streamUrl
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.v(TAG, "[SRC]  kw   title-retry status=${SourceCall.classify(e).name.lowercase()} detail=${e.javaClass.simpleName} q=\"$query\"")
        }
        return null
    }

    /** Returns up to [limit] rids, null when the search itself failed. */
    private suspend fun searchRids(query: String, limit: Int, reporter: HttpReporter? = null): List<String>? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        reporter?.url = "search.kuwo.cn/r.s"
        val req = Request.Builder()
            .url(SEARCH_URL.format(encoded, if (limit >= SEARCH_RN) SEARCH_RN else limit))
            .header("User-Agent", HttpPolicy.DESKTOP_UA)
            .build()

        searchClient.newCall(req).awaitResponse().use { resp ->
            reporter?.httpCode = resp.code
            if (!resp.isSuccessful) throw HttpCodeException(resp.code, reporter?.url)
            val body = resp.body?.string() ?: throw HttpCodeException(resp.code, reporter?.url)
            val root = JsonParser.parseString(normalizeKuwoJson(body)).asJsonObject
            val abslist = root.get("abslist")?.takeIf { it.isJsonArray }?.asJsonArray ?: run {
                reporter?.detail = "field=abslist"
                return emptyList()
            }

            val idList = mutableListOf<String>()
            for (i in 0 until minOf(limit, abslist.size())) {
                val rid = ridOf(abslist[i].asJsonObject)
                if (!rid.isNullOrEmpty()) idList.add(rid)
            }
            return idList
        }
    }

    private suspend fun convertRid(rid: String): String? {
        convertClient.newCall(Request.Builder().url(antiServerUrl(rid)).build()).awaitResponse().use { resp ->
            if (!resp.isSuccessful) return null
            val streamUrl = resp.body?.string()?.trim()
            return if (!streamUrl.isNullOrEmpty() && streamUrl.startsWith("http") && !isKuwoPromoAudio(streamUrl)) {
                streamUrl
            } else null
        }
    }

    suspend fun searchSongs(query: String, reporter: HttpReporter = HttpReporter()): List<Song> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        reporter.url = "search.kuwo.cn/r.s"
        val req = Request.Builder()
            .url(SEARCH_URL.format(encoded, SEARCH_RN))
            .header("User-Agent", HttpPolicy.DESKTOP_UA)
            .build()

        searchClient.newCall(req).awaitResponse().use { resp ->
            reporter.httpCode = resp.code
            if (!resp.isSuccessful) throw HttpCodeException(resp.code, reporter.url)
            val body = resp.body?.string() ?: throw HttpCodeException(resp.code, reporter.url)
            val root = JsonParser.parseString(normalizeKuwoJson(body)).asJsonObject
            val abslist = root.get("abslist")?.takeIf { it.isJsonArray }?.asJsonArray ?: run {
                reporter.detail = "field=abslist"
                return@withContext emptyList()
            }

            val results = ArrayList<Song>(abslist.size())
            for (item in abslist) {
                val obj = item.asJsonObject
                val rawName = str(obj, "SONGNAME") ?: continue
                val rawArtist = str(obj, "ARTIST") ?: "群星"
                val duration = (str(obj, "DURATION")?.toLongOrNull() ?: 0L) * 1000L
                val rid = ridOf(obj) ?: continue

                var cover = ""
                val webPic = str(obj, "web_albumpic_short")
                val artistPic = str(obj, "web_artistpic_short")
                if (!webPic.isNullOrEmpty()) {
                    cover = "https://img4.kuwo.cn/star/albumcover/$webPic"
                } else if (!artistPic.isNullOrEmpty()) {
                    cover = "https://img4.kuwo.cn/star/starheads/$artistPic"
                }

                results.add(
                    Song(
                        id = "kw_$rid",
                        title = cleanHtmlEntities(rawName),
                        artist = cleanHtmlEntities(rawArtist),
                        coverUrl = cover,
                        audioUrl = "kuwo://$rid",
                        durationMs = duration,
                        source = HttpPolicy.KUWO
                    )
                )
            }
            results
        }
    }
}
