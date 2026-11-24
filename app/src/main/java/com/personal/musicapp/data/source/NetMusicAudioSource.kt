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

class NetMusicAudioSource : AudioSource {

    companion object {
        private const val TAG = "Netease"
        private const val SEARCH_URL = "https://music.163.com/api/search/get/web?s=%s&type=1&offset=0&total=true&limit=%d"
        private const val COVER_URL = "https://music.163.com/api/song/detail/?ids=%s"

        /** Covers are decorative: only fetched when this much of the search budget is left. */
        private const val COVER_MIN_REMAINING_MS = 1500L

        fun outerUrl(id: Long): String = "https://music.163.com/song/media/outer/url?id=$id.mp3"

        private fun headers() = mapOf(
            "User-Agent" to HttpPolicy.DESKTOP_UA,
            "Referer" to "https://music.163.com/"
        )
    }

    override val sourceName: String = HttpPolicy.NETEASE

    private val client by lazy { HttpPolicy.searchClient(sourceName) }
    private val coverClient by lazy { HttpPolicy.coverClient() }

    override fun canHandleLink(text: String): Boolean = false

    override suspend fun parseShareLink(text: String): Song? = null

    override suspend fun search(query: String): List<Song> = search(query, HttpReporter())

    override suspend fun search(query: String, reporter: HttpReporter): List<Song> =
        withContext(Dispatchers.IO) { runSearch(query, reporter, withCovers = true) }

    /** Exact title (+artist) lookup used when another source has the song but no free stream. */
    suspend fun findByTitle(title: String, artist: String): Song? {
        val wanted = title.trim()
        val reporter = HttpReporter()
        val candidates = runSearch("$wanted ${artist.trim()}", reporter, withCovers = false)
            .ifEmpty { runSearch(wanted, reporter, withCovers = false) }
        val artistMatch = artist.isNotBlank() && artist != "群星" && artist != "未知歌曲"
        return candidates.firstOrNull { song ->
            song.title.equals(wanted, ignoreCase = true) &&
                (!artistMatch || song.artist.split("/", "、", ",", "&").first().trim().equals(artist.trim(), ignoreCase = true))
        }
    }

    private suspend fun runSearch(query: String, reporter: HttpReporter, withCovers: Boolean): List<Song> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val started = System.nanoTime()

        reporter.url = "music.163.com/api/search/get/web"
        val req = Request.Builder()
            .url(SEARCH_URL.format(URLEncoder.encode(trimmed, "UTF-8"), 30))
            .apply { headers().forEach { (k, v) -> header(k, v) } }
            .build()

        val results = ArrayList<Song>()
        client.newCall(req).awaitResponse().use { resp ->
            reporter.httpCode = resp.code
            if (!resp.isSuccessful) throw HttpCodeException(resp.code, reporter.url)
            val body = resp.body?.string() ?: throw HttpCodeException(resp.code, reporter.url)
            results.addAll(parseSearchBody(body, reporter))
        }

        // Results are complete here; a cover failure can only cost covers.
        if (!withCovers || results.isEmpty()) return results
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        if (searchDeadlineMs - elapsedMs <= COVER_MIN_REMAINING_MS) return results
        val ids = results.mapNotNull { it.id.removePrefix("net_").toLongOrNull() }
        return applyCovers(results, fetchCovers(ids))
    }

    internal fun parseSearchBody(body: String, reporter: HttpReporter): List<Song> {
        val root = JsonParser.parseString(body).asJsonObject
        val resultObj = root.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: run { reporter.detail = "field=result"; return emptyList() }
        val songsArray = resultObj.get("songs")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: run { reporter.detail = "field=result.songs"; return emptyList() }

        val results = ArrayList<Song>()
        for (item in songsArray) {
            val songObj = item.asJsonObject
            val id = songObj.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: continue
            val name = songObj.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "未知歌曲"
            val duration = songObj.get("duration")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

            var artistName = "群星"
            val artistsArray = songObj.get("artists")?.takeIf { it.isJsonArray }?.asJsonArray
            if (artistsArray != null && artistsArray.size() > 0) {
                artistName = artistsArray[0].asJsonObject.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "群星"
            }
            val picUrl = songObj.get("album")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("picUrl")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                .replace("http://", "https://")

            results.add(
                Song(
                    id = "net_$id",
                    title = name,
                    artist = artistName,
                    coverUrl = if (picUrl.isEmpty()) "" else "$picUrl?param=400y400",
                    audioUrl = outerUrl(id),
                    durationMs = duration,
                    source = sourceName
                )
            )
        }
        return results
    }

    /** Returns a new list; songs without a fetched cover keep their own row unchanged. */
    internal fun applyCovers(results: List<Song>, covers: Map<Long, String>): List<Song> {
        if (covers.isEmpty()) return results
        return results.map { song ->
            val cover = covers[song.id.removePrefix("net_").toLongOrNull()] ?: return@map song
            song.copy(coverUrl = cover)
        }
    }

    private suspend fun fetchCovers(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        try {
            val idsParam = ids.joinToString(prefix = "[", postfix = "]", separator = ",")
            val req = Request.Builder()
                .url(COVER_URL.format(idsParam))
                .apply { headers().forEach { (k, v) -> header(k, v) } }
                .build()
            coverClient.newCall(req).awaitResponse().use { resp ->
                if (!resp.isSuccessful) return emptyMap()
                val root = JsonParser.parseString(resp.body?.string() ?: return emptyMap()).asJsonObject
                val songsArray = root.get("songs")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
                val covers = HashMap<Long, String>()
                for (item in songsArray) {
                    val sObj = item.asJsonObject
                    val el: JsonElement = sObj.get("id") ?: continue
                    if (!el.isJsonPrimitive) continue
                    val id = el.asLong
                    val picUrl = sObj.get("album")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("picUrl")?.takeIf { it.isJsonPrimitive }?.asString
                    if (!picUrl.isNullOrEmpty()) {
                        covers[id] = picUrl.replace("http://", "https://") + "?param=400y400"
                    }
                }
                return covers
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.v(TAG, "[SRC]  net  cover-fetch status=${SourceCall.classify(e).name.lowercase().replace('_', '-')} detail=${e.javaClass.simpleName}")
            return emptyMap()
        }
    }
}
