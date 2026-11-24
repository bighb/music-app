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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.regex.Pattern

/** Outcome of one InnerTube player call. `url` is set only on a real free stream. */
data class YtResolveResult(
    val url: String?,
    val httpCode: Int = 0,
    val status: SourceStatus = SourceStatus.ERROR,
    val detail: String? = null
)

class YouTubeAudioSource(
    private val cookieProvider: () -> String? = { null }
) : AudioSource {

    companion object {
        const val SAFARI_USER_AGENT = HttpPolicy.YOUTUBE_USER_AGENT
        private const val TAG = "YouTube"
        private const val VISITOR_DATA_NEGATIVE_CACHE_MS = 10 * 60 * 1000L
    }

    override val sourceName: String = HttpPolicy.YOUTUBE

    @Volatile
    private var cachedVisitorData: String? = null

    @Volatile
    private var visitorDataFailedAt: Long? = null

    private val searchClient by lazy { HttpPolicy.searchClient(sourceName) }
    private val bootstrapClient by lazy { HttpPolicy.resolveClient(HttpPolicy.YOUTUBE_BOOTSTRAP_MS) }
    private val playerClient by lazy { HttpPolicy.playerClient() }

    private val youtubeUrlPattern = Pattern.compile(
        "(?:https?://)?(?:www\\.|music\\.)?(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/)|youtu\\.be/)([a-zA-Z0-9_-]{11})"
    )

    override fun canHandleLink(text: String): Boolean {
        return text.contains("youtube.com") || text.contains("youtu.be")
    }

    override suspend fun parseShareLink(text: String): Song? = withContext(Dispatchers.IO) {
        val matcher = youtubeUrlPattern.matcher(text)
        val videoId = if (matcher.find()) {
            matcher.group(1)
        } else {
            val vParamMatcher = Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})").matcher(text)
            if (vParamMatcher.find()) vParamMatcher.group(1) else null
        } ?: return@withContext null

        return@withContext fetchSongDetails(videoId)
    }

    private fun generateSapisidHash(sapisid: String, origin: String): String {
        val now = System.currentTimeMillis() / 1000
        val raw = "$now $sapisid $origin"
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(raw.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${now}_$hex"
    }

    private fun applyAuthHeaders(builder: Request.Builder, origin: String) {
        val cookie = cookieProvider()?.trim()
        if (!cookie.isNullOrEmpty()) {
            builder.header("Cookie", cookie)
            val sapisidMatch = Regex("(?:SAPISID|__Secure-3PAPISID)=([^;]+)").find(cookie)
            if (sapisidMatch != null) {
                val sapisid = sapisidMatch.groupValues[1].trim()
                val authHeader = generateSapisidHash(sapisid, origin)
                builder.header("Authorization", authHeader)
                builder.header("X-Origin", origin)
            }
        }
    }

    override suspend fun search(query: String): List<Song> = search(query, HttpReporter())

    override suspend fun search(query: String, reporter: HttpReporter): List<Song> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        if (canHandleLink(trimmed)) {
            val song = parseShareLink(trimmed)
            return@withContext if (song != null) listOf(song) else emptyList()
        }

        try {
            val payload = JsonObject().apply {
                add("context", JsonObject().apply {
                    add("client", JsonObject().apply {
                        addProperty("clientName", "WEB_REMIX")
                        addProperty("clientVersion", "1.20240318.01.00")
                        addProperty("hl", "zh-CN")
                        addProperty("gl", "US")
                    })
                })
                addProperty("query", trimmed)
            }

            reporter.url = "music.youtube.com/youtubei/v1/search"
            val reqBuilder = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search")
                .header("Content-Type", "application/json")
                .header("User-Agent", SAFARI_USER_AGENT)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))

            applyAuthHeaders(reqBuilder, "https://music.youtube.com")

            searchClient.newCall(reqBuilder.build()).awaitResponse().use { resp ->
                reporter.httpCode = resp.code
                if (!resp.isSuccessful) throw HttpCodeException(resp.code, reporter.url)
                val bodyStr = resp.body?.string() ?: throw HttpCodeException(resp.code, reporter.url)
                val root = JsonParser.parseString(bodyStr).asJsonObject
                root.getAsJsonObject("responseContext")?.get("visitorData")?.asString?.let {
                    if (it.isNotEmpty()) cachedVisitorData = it
                }
                return@withContext parseSearchResults(root)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private fun parseSearchResults(root: JsonObject): List<Song> {
        val renderers = mutableListOf<JsonObject>()
        findMusicRenderers(root, renderers)

        val results = mutableListOf<Song>()
        val seenVids = mutableSetOf<String>()

        for (item in renderers) {
            val videoId = extractVideoId(item) ?: continue
            if (seenVids.contains(videoId)) continue
            seenVids.add(videoId)

            val flexCols = item.getAsJsonArray("flexColumns") ?: continue
            var title = ""
            var artist = "YouTube"
            var durationMs = 0L

            if (flexCols.size() > 0) {
                title = extractTextFromFlexColumn(flexCols[0].asJsonObject)
            }
            if (flexCols.size() > 1) {
                val runs = flexCols[1].asJsonObject
                    .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.getAsJsonObject("text")
                    ?.getAsJsonArray("runs")
                if (runs != null && runs.size() > 0) {
                    val texts = mutableListOf<String>()
                    for (r in runs) {
                        val t = r.asJsonObject.get("text")?.asString?.trim() ?: ""
                        if (t.isNotEmpty() && t != "•" && t != "·") {
                            texts.add(t)
                        }
                    }
                    if (texts.isNotEmpty()) {
                        if (texts[0] in listOf("歌曲", "视频", "单曲") && texts.size > 1) {
                            artist = texts[1]
                        } else {
                            artist = texts[0]
                        }
                        for (t in texts) {
                            if (t.contains(":")) {
                                durationMs = parseDuration(t)
                            }
                        }
                    }
                }
            }

            if (title.isEmpty()) continue

            var coverUrl = ""
            val thumbnails = item.getAsJsonObject("thumbnail")
                ?.getAsJsonObject("musicThumbnailRenderer")
                ?.getAsJsonObject("thumbnail")
                ?.getAsJsonArray("thumbnails")
            if (thumbnails != null && thumbnails.size() > 0) {
                coverUrl = thumbnails[thumbnails.size() - 1].asJsonObject.get("url")?.asString ?: ""
            }
            if (coverUrl.isEmpty()) {
                coverUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            }

            results.add(
                Song(
                    id = "yt_$videoId",
                    title = title,
                    artist = artist,
                    coverUrl = coverUrl,
                    audioUrl = "youtube://$videoId",
                    durationMs = durationMs,
                    source = sourceName
                )
            )
        }
        return results
    }

    private fun findMusicRenderers(element: JsonElement, output: MutableList<JsonObject>) {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            if (obj.has("musicResponsiveListItemRenderer")) {
                output.add(obj.getAsJsonObject("musicResponsiveListItemRenderer"))
            }
            for (entry in obj.entrySet()) {
                findMusicRenderers(entry.value, output)
            }
        } else if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                findMusicRenderers(child, output)
            }
        }
    }

    private fun extractVideoId(item: JsonObject): String? {
        val pData = item.getAsJsonObject("playlistItemData")
        val directVid = pData?.get("videoId")?.asString
        if (!directVid.isNullOrEmpty()) return directVid

        val overlay = item.getAsJsonObject("overlay")
            ?.getAsJsonObject("musicItemThumbnailOverlayRenderer")
            ?.getAsJsonObject("content")
            ?.getAsJsonObject("musicPlayButtonRenderer")
            ?.getAsJsonObject("playNavigationEndpoint")
            ?.getAsJsonObject("watchEndpoint")
            ?.get("videoId")?.asString
        if (!overlay.isNullOrEmpty()) return overlay

        val flexCols = item.getAsJsonArray("flexColumns")
        if (flexCols != null) {
            for (col in flexCols) {
                val runs = col.asJsonObject
                    .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.getAsJsonObject("text")
                    ?.getAsJsonArray("runs")
                if (runs != null) {
                    for (r in runs) {
                        val v = r.asJsonObject
                            .getAsJsonObject("navigationEndpoint")
                            ?.getAsJsonObject("watchEndpoint")
                            ?.get("videoId")?.asString
                        if (!v.isNullOrEmpty()) return v
                    }
                }
            }
        }

        val thumbnails = item.getAsJsonObject("thumbnail")
            ?.getAsJsonObject("musicThumbnailRenderer")
            ?.getAsJsonObject("thumbnail")
            ?.getAsJsonArray("thumbnails")
        if (thumbnails != null) {
            val viPattern = Pattern.compile("vi/([a-zA-Z0-9_-]{11})/")
            for (thumb in thumbnails) {
                val url = thumb.asJsonObject.get("url")?.asString ?: continue
                val m = viPattern.matcher(url)
                if (m.find()) return m.group(1)
            }
        }

        return null
    }

    private fun extractTextFromFlexColumn(colObj: JsonObject): String {
        val runs = colObj
            .getAsJsonObject("musicResponsiveListItemFlexColumnRenderer")
            ?.getAsJsonObject("text")
            ?.getAsJsonArray("runs") ?: return ""
        val sb = StringBuilder()
        for (r in runs) {
            val t = r.asJsonObject.get("text")?.asString ?: ""
            sb.append(t)
        }
        return sb.toString().trim()
    }

    private fun parseDuration(text: String): Long {
        val parts = text.split(":")
        return when (parts.size) {
            2 -> {
                val m = parts[0].trim().toLongOrNull() ?: 0L
                val s = parts[1].trim().toLongOrNull() ?: 0L
                (m * 60 + s) * 1000L
            }
            3 -> {
                val h = parts[0].trim().toLongOrNull() ?: 0L
                val m = parts[1].trim().toLongOrNull() ?: 0L
                val s = parts[2].trim().toLongOrNull() ?: 0L
                (h * 3600 + m * 60 + s) * 1000L
            }
            else -> 0L
        }
    }

    /**
     * Visitor data is optional: InnerTube answers without it. So the bootstrap GET gets a
     * tiny budget and a failure is remembered, instead of stalling every playback.
     */
    private suspend fun ensureVisitorData(videoId: String? = null): String? = withContext(Dispatchers.IO) {
        val cached = cachedVisitorData
        if (!cached.isNullOrEmpty()) return@withContext cached

        val now = System.currentTimeMillis()
        val failedAt = visitorDataFailedAt
        if (failedAt != null && now - failedAt < VISITOR_DATA_NEGATIVE_CACHE_MS) return@withContext null

        try {
            val url = if (!videoId.isNullOrEmpty()) {
                "https://www.youtube.com/watch?v=$videoId"
            } else {
                "https://www.youtube.com"
            }
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", SAFARI_USER_AGENT)
                .header("Cookie", "PREF=hl=en&tz=UTC; SOCS=CAI")
                .build()
            bootstrapClient.newCall(req).awaitResponse().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val regex = Regex("\"(?:visitorData|VISITOR_DATA)\":\"([^\"]+)\"")
                    val match = regex.find(html)
                    val vData = match?.groupValues?.get(1)
                    if (!vData.isNullOrEmpty()) {
                        cachedVisitorData = vData
                        visitorDataFailedAt = null
                        return@withContext vData
                    }
                }
                AppLogger.v(TAG, "[SRC]  yt   visitor-data bootstrap http=${resp.code} status=empty")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.v(TAG, "[SRC]  yt   visitor-data bootstrap status=${SourceCall.classify(e).name.lowercase().replace('_', '-')} detail=${e.javaClass.simpleName}")
        }
        visitorDataFailedAt = now
        return@withContext cachedVisitorData
    }

    suspend fun resolveStreamUrl(videoId: String): String? = resolveStream(videoId).url

    /** Reads `playabilityStatus` first so "copyright-blocked" and "we timed out" stay distinct. */
    suspend fun resolveStream(videoId: String): YtResolveResult = withContext(Dispatchers.IO) {
        val reporter = HttpReporter()
        try {
            val visitorData = ensureVisitorData(videoId)
            // Use VISIONOS client: InnerTube delivers non-throttled direct stream URLs without requiring PO tokens
            val clientObj = JsonObject().apply {
                addProperty("clientName", "VISIONOS")
                addProperty("clientVersion", "1.02")
                addProperty("deviceMake", "Apple")
                addProperty("deviceModel", "RealityDevice17,1")
                addProperty("userAgent", SAFARI_USER_AGENT)
                addProperty("osName", "visionOS")
                addProperty("osVersion", "26.5.23O471")
                addProperty("hl", "en")
                addProperty("timeZone", "UTC")
                addProperty("utcOffsetMinutes", 0)
                if (!visitorData.isNullOrEmpty()) {
                    addProperty("visitorData", visitorData)
                }
            }

            val payload = JsonObject().apply {
                add("context", JsonObject().apply {
                    add("client", clientObj)
                })
                addProperty("videoId", videoId)
                add("playbackContext", JsonObject().apply {
                    add("contentPlaybackContext", JsonObject().apply {
                        addProperty("html5Preference", "HTML5_PREF_WANTS")
                    })
                })
                addProperty("contentCheckOk", true)
                addProperty("racyCheckOk", true)
            }

            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("Content-Type", "application/json")
                .header("User-Agent", SAFARI_USER_AGENT)
                .header("X-Youtube-Client-Name", "101")
                .header("X-Youtube-Client-Version", "1.02")
                .header("Origin", "https://www.youtube.com")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))

            reporter.url = "www.youtube.com/youtubei/v1/player"
            applyAuthHeaders(reqBuilder, "https://www.youtube.com")

            playerClient.newCall(reqBuilder.build()).awaitResponse().use { resp ->
                reporter.httpCode = resp.code
                if (!resp.isSuccessful) {
                    AppLogger.w(TAG, "[SRC]  yt   id=$videoId http=${resp.code} status=http-error")
                    return@withContext YtResolveResult(null, resp.code, SourceStatus.HTTP_ERROR)
                }
                val body = resp.body?.string() ?: return@withContext YtResolveResult(null, resp.code, SourceStatus.PARSE_ERROR)
                val root = JsonParser.parseString(body).asJsonObject

                val playability = root.getAsJsonObject("playabilityStatus")
                val playabilityStatus = playability?.get("status")?.takeIf { it.isJsonPrimitive }?.asString
                val reason = playability?.get("reason")?.takeIf { it.isJsonPrimitive }?.asString

                // Prefer the best adaptive audio; progressive `formats` is the free-tier fallback.
                val url = pickBestAudioUrl(root, "adaptiveFormats") ?: pickBestAudioUrl(root, "formats")
                if (url != null) return@withContext YtResolveResult(url, resp.code, SourceStatus.OK)

                if (playabilityStatus != null && playabilityStatus != "OK") {
                    AppLogger.w(TAG, "[SRC]  yt   id=$videoId http=${resp.code} status=playability-blocked reason=$playabilityStatus")
                    return@withContext YtResolveResult(null, resp.code, SourceStatus.PLAYABILITY_BLOCKED, reason ?: playabilityStatus)
                }
                AppLogger.v(TAG, "[SRC]  yt   id=$videoId http=${resp.code} status=empty field=streamingData")
                return@withContext YtResolveResult(null, resp.code, SourceStatus.EMPTY, "no audio formats")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = SourceCall.classify(e)
            AppLogger.v(TAG, "[SRC]  yt   id=$videoId http=${reporter.httpCode} status=${status.name.lowercase().replace('_', '-')} detail=${e.javaClass.simpleName}")
            return@withContext YtResolveResult(null, reporter.httpCode, status, e.javaClass.simpleName)
        }
    }

    private fun pickBestAudioUrl(root: JsonObject, arrayName: String): String? {
        val formats = root.getAsJsonObject("streamingData")?.getAsJsonArray(arrayName) ?: return null
        var bestUrl: String? = null
        var maxBitrate = 0L
        for (f in formats) {
            val obj = f.asJsonObject
            val mimeEl = obj.get("mimeType")
            if (mimeEl == null || !mimeEl.isJsonPrimitive || !mimeEl.asString.contains("audio")) continue
            val urlEl = obj.get("url")
            if (urlEl == null || !urlEl.isJsonPrimitive) continue
            val url = urlEl.asString
            if (url.isEmpty()) continue
            val bitrate = obj.get("bitrate")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
            if (bitrate > maxBitrate) {
                maxBitrate = bitrate
                bestUrl = url
            }
        }
        return bestUrl
    }

    private suspend fun fetchSongDetails(videoId: String): Song? = withContext(Dispatchers.IO) {
        try {
            val visitorData = ensureVisitorData(videoId)
            val clientObj = JsonObject().apply {
                addProperty("clientName", "VISIONOS")
                addProperty("clientVersion", "1.02")
                addProperty("deviceMake", "Apple")
                addProperty("deviceModel", "RealityDevice17,1")
                addProperty("userAgent", SAFARI_USER_AGENT)
                addProperty("osName", "visionOS")
                addProperty("osVersion", "26.5.23O471")
                addProperty("hl", "zh-CN")
                addProperty("timeZone", "UTC")
                addProperty("utcOffsetMinutes", 0)
                if (!visitorData.isNullOrEmpty()) {
                    addProperty("visitorData", visitorData)
                }
            }

            val payload = JsonObject().apply {
                add("context", JsonObject().apply {
                    add("client", clientObj)
                })
                addProperty("videoId", videoId)
                add("playbackContext", JsonObject().apply {
                    add("contentPlaybackContext", JsonObject().apply {
                        addProperty("html5Preference", "HTML5_PREF_WANTS")
                    })
                })
                addProperty("contentCheckOk", true)
                addProperty("racyCheckOk", true)
            }

            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("Content-Type", "application/json")
                .header("User-Agent", SAFARI_USER_AGENT)
                .header("X-Youtube-Client-Name", "101")
                .header("X-Youtube-Client-Version", "1.02")
                .header("Origin", "https://www.youtube.com")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))

            applyAuthHeaders(reqBuilder, "https://www.youtube.com")

            playerClient.newCall(reqBuilder.build()).awaitResponse().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val root = JsonParser.parseString(body).asJsonObject

                val videoDetails = root.getAsJsonObject("videoDetails") ?: return@withContext null
                val title = videoDetails.get("title")?.asString ?: "YouTube 音频"
                val author = videoDetails.get("author")?.asString ?: "YouTube 创作者"
                val lengthSec = videoDetails.get("lengthSeconds")?.asLong ?: 0L
                val coverUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                return@withContext Song(
                    id = "yt_$videoId",
                    title = title,
                    artist = author,
                    coverUrl = coverUrl,
                    audioUrl = "youtube://$videoId",
                    durationMs = lengthSec * 1000L,
                    source = sourceName
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.v(TAG, "[SRC]  yt   id=$videoId link-parse status=${SourceCall.classify(e).name.lowercase().replace('_', '-')} detail=${e.javaClass.simpleName}")
            null
        }
    }
}
