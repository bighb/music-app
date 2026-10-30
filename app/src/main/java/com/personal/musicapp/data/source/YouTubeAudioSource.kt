package com.personal.musicapp.data.source

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personal.musicapp.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeAudioSource(
    private val cookieProvider: () -> String? = { null }
) : AudioSource {

    override val sourceName: String = "YouTube"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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

    override suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
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

            val reqBuilder = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search")
                .header("Content-Type", "application/json")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                )
                .post(payload.toString().toRequestBody("application/json".toMediaType()))

            applyAuthHeaders(reqBuilder, "https://music.youtube.com")

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val bodyStr = resp.body?.string() ?: return@withContext emptyList()
                val root = JsonParser.parseString(bodyStr).asJsonObject
                return@withContext parseSearchResults(root)
            }
        } catch (e: Exception) {
            emptyList()
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

    suspend fun resolveStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("context", JsonObject().apply {
                    add("client", JsonObject().apply {
                        addProperty("clientName", "ANDROID")
                        addProperty("clientVersion", "20.10.38")
                        addProperty("osName", "Android")
                        addProperty("osVersion", "11")
                        addProperty("hl", "zh-CN")
                        addProperty("gl", "US")
                    })
                })
                addProperty("videoId", videoId)
                addProperty("contentCheckOk", true)
                addProperty("racyCheckOk", true)
            }

            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .header("Content-Type", "application/json")
                .header("User-Agent", "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))

            applyAuthHeaders(reqBuilder, "https://www.youtube.com")

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val root = JsonParser.parseString(body).asJsonObject

                val streamingData = root.getAsJsonObject("streamingData") ?: return@withContext null
                val adaptiveFormats = streamingData.getAsJsonArray("adaptiveFormats") ?: return@withContext null

                var bestUrl: String? = null
                var maxBitrate = 0L

                for (f in adaptiveFormats) {
                    val obj = f.asJsonObject
                    val mime = obj.get("mimeType")?.asString ?: ""
                    if (!mime.contains("audio")) continue
                    val url = obj.get("url")?.asString
                    if (url.isNullOrEmpty()) continue

                    val bitrate = obj.get("bitrate")?.asLong ?: 0L
                    if (bitrate > maxBitrate) {
                        maxBitrate = bitrate
                        bestUrl = url
                    }
                }

                return@withContext bestUrl
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchSongDetails(videoId: String): Song? = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                add("context", JsonObject().apply {
                    add("client", JsonObject().apply {
                        addProperty("clientName", "ANDROID")
                        addProperty("clientVersion", "20.10.38")
                        addProperty("osName", "Android")
                        addProperty("osVersion", "11")
                        addProperty("hl", "zh-CN")
                        addProperty("gl", "US")
                    })
                })
                addProperty("videoId", videoId)
                addProperty("contentCheckOk", true)
                addProperty("racyCheckOk", true)
            }

            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .header("Content-Type", "application/json")
                .header("User-Agent", "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))

            applyAuthHeaders(reqBuilder, "https://www.youtube.com")

            client.newCall(reqBuilder.build()).execute().use { resp ->
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
        } catch (e: Exception) {
            null
        }
    }
}
