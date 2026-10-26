package com.personal.musicapp.data.source

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.personal.musicapp.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class DouyinAudioSource(
    private val fallbackMusicSource: NetMusicAudioSource? = null
) : AudioSource {

    override val sourceName: String = "抖音原声"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val mobileUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
    private val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val urlPattern = Pattern.compile("https?://[a-zA-Z0-9./_?&=%-]+")

    @Volatile
    private var cachedTtwid: String? = null

    override fun canHandleLink(text: String): Boolean {
        return text.contains("douyin.com") || text.contains("iesdouyin.com")
    }

    private fun getOrRegisterTtwid(): String {
        cachedTtwid?.let { return it }
        try {
            val registerUrl = "https://ttwid.bytedance.com/ttwid/union/register/"
            val jsonBody = """{"region":"cn","aid":1768,"needFid":false,"service":"www.ixigua.com","migrate_info":{"ticket":"","src_subaid":-1},"cbUrlProtocol":"https","union":true}"""
            val req = Request.Builder()
                .url(registerUrl)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val cookieHeaders = resp.headers("Set-Cookie")
                for (header in cookieHeaders) {
                    if (header.contains("ttwid=")) {
                        val cookieVal = header.substringAfter("ttwid=").substringBefore(";")
                        val ttwid = "ttwid=$cookieVal"
                        cachedTtwid = ttwid
                        return ttwid
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "ttwid=1"
    }

    override suspend fun parseShareLink(text: String): Song? = withContext(Dispatchers.IO) {
        val matcher = urlPattern.matcher(text)
        if (!matcher.find()) return@withContext null
        val rawUrl = matcher.group()

        try {
            // Extract possible title from text
            val extractedTitle = extractTitleFromShareText(text)

            // Step 1: Follow redirect to get actual video ID
            val redirectRequest = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", mobileUserAgent)
                .build()

            val redirectResp = client.newCall(redirectRequest).execute()
            val finalUrl = redirectResp.request.url.toString()
            redirectResp.close()

            val idPattern = Pattern.compile("(?:video|note)/(\\d+)")
            val idMatcher = idPattern.matcher(finalUrl)
            val awemeId = if (idMatcher.find()) {
                idMatcher.group(1)
            } else {
                val modalPattern = Pattern.compile("modal_id=(\\d+)")
                val modalMatcher = modalPattern.matcher(finalUrl)
                if (modalMatcher.find()) modalMatcher.group(1) else null
            }

            // Step 2: Try Strategy A - Direct Page _ROUTER_DATA
            if (!awemeId.isNullOrEmpty()) {
                val parsed = parseViaRouterData(awemeId)
                if (parsed != null) return@withContext parsed

                // Step 3: Try Strategy B - Web Detail API
                val parsedDetail = parseViaWebDetail(awemeId)
                if (parsedDetail != null) return@withContext parsedDetail
            }

            // Step 4: Try Strategy C - Free Multi-gateway fallback
            val fallback = parseViaPublicGateway(rawUrl)
            if (fallback != null) return@withContext fallback

            // Step 5: If title was extracted, search full music library
            if (!extractedTitle.isNullOrEmpty() && fallbackMusicSource != null) {
                val searchList = fallbackMusicSource.search(extractedTitle)
                if (searchList.isNotEmpty()) {
                    return@withContext searchList[0].copy(
                        title = "${searchList[0].title} (来自抖音分享)",
                        source = sourceName
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext null
    }

    private fun extractTitleFromShareText(text: String): String? {
        val bracketPattern = Pattern.compile("【(.*?)】")
        val m = bracketPattern.matcher(text)
        if (m.find()) {
            val rawGroup = m.group(1)
            if (rawGroup != null) {
                val title = rawGroup.replace("的作品", "").replace("的视频", "").trim()
                if (title.isNotEmpty()) return title
            }
        }
        val cleaned = text.replace(urlPattern.toRegex(), "").replace("[0-9]+\\.[0-9]+".toRegex(), "").replace("复制此链接.*".toRegex(), "").trim()
        return if (cleaned.length > 2) cleaned.take(20) else null
    }

    private fun parseViaRouterData(awemeId: String): Song? {
        return try {
            val ttwid = getOrRegisterTtwid()
            val shareUrl = "https://www.iesdouyin.com/share/video/$awemeId"
            val req = Request.Builder()
                .url(shareUrl)
                .header("User-Agent", mobileUserAgent)
                .header("Cookie", ttwid)
                .header("Referer", "https://www.douyin.com/")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null

                val routerIdx = html.indexOf("_ROUTER_DATA")
                if (routerIdx == -1) return null

                val startIdx = html.indexOf('{', routerIdx)
                val endIdx = html.indexOf("</script>", startIdx)
                if (startIdx == -1 || endIdx == -1) return null

                var jsonStr = html.substring(startIdx, endIdx).trim()
                if (jsonStr.endsWith(";")) {
                    jsonStr = jsonStr.substring(0, jsonStr.length - 1)
                }

                val root = JsonParser.parseString(jsonStr).asJsonObject
                val loaderData = root.getAsJsonObject("loaderData") ?: return null

                var targetItem: JsonObject? = null
                for (entry in loaderData.entrySet()) {
                    // CRITICAL FIX: check isJsonObject to avoid null pointer / IllegalStateException
                    if (!entry.value.isJsonObject) continue
                    val pageObj = entry.value.asJsonObject
                    val videoInfoRes = pageObj.getAsJsonObject("videoInfoRes") ?: continue
                    val itemList = videoInfoRes.getAsJsonArray("item_list")
                    if (itemList != null && itemList.size() > 0 && itemList[0].isJsonObject) {
                        targetItem = itemList[0].asJsonObject
                        break
                    }
                }

                if (targetItem == null) return null

                extractSongFromAwemeJson(awemeId, targetItem)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseViaWebDetail(awemeId: String): Song? {
        return try {
            val ttwid = getOrRegisterTtwid()
            val detailUrl = "https://www.douyin.com/aweme/v1/web/aweme/detail/?device_platform=webapp&aid=6383&channel=channel_pc_web&aweme_id=$awemeId&pc_client_type=1&version_code=190500&version_name=19.5.0"
            val req = Request.Builder()
                .url(detailUrl)
                .header("User-Agent", desktopUserAgent)
                .header("Cookie", ttwid)
                .header("Referer", "https://www.douyin.com/")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JsonParser.parseString(body).asJsonObject
                val awemeDetail = root.getAsJsonObject("aweme_detail") ?: return null
                extractSongFromAwemeJson(awemeId, awemeDetail)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun extractSongFromAwemeJson(awemeId: String, item: JsonObject): Song? {
        val desc = item.get("desc")?.asString ?: "抖音原声"
        val author = item.getAsJsonObject("author")?.get("nickname")?.asString ?: "抖音创作者"

        val musicObj = item.getAsJsonObject("music")
        val musicTitle = musicObj?.get("title")?.asString
        val musicAuthor = musicObj?.get("author")?.asString
        var audioUrl: String? = null
        var coverUrl = ""
        var durationMs = 0L

        if (musicObj != null) {
            val playUrlObj = musicObj.getAsJsonObject("play_url")
            val urlList = playUrlObj?.getAsJsonArray("url_list")
            if (urlList != null && urlList.size() > 0) {
                audioUrl = urlList[0].asString
            } else {
                val uri = playUrlObj?.get("uri")?.asString
                if (!uri.isNullOrEmpty()) {
                    audioUrl = "https://aweme.snssdk.com/aweme/v1/play/?video_id=$uri&ratio=1080p&line=0"
                }
            }

            val coverList = musicObj.getAsJsonObject("cover_large")?.getAsJsonArray("url_list")
                ?: musicObj.getAsJsonObject("cover_hd")?.getAsJsonArray("url_list")
            if (coverList != null && coverList.size() > 0) {
                coverUrl = coverList[0].asString
            }
            durationMs = (musicObj.get("duration")?.asLong ?: 0L) * 1000L
        }

        // Fallback to video audio track
        if (audioUrl.isNullOrEmpty()) {
            val videoObj = item.getAsJsonObject("video")
            val playAddrList = videoObj?.getAsJsonObject("play_addr")?.getAsJsonArray("url_list")
            if (playAddrList != null && playAddrList.size() > 0) {
                audioUrl = playAddrList[0].asString.replace("playwm", "play")
            }
            if (coverUrl.isEmpty()) {
                val videoCovers = videoObj?.getAsJsonObject("cover")?.getAsJsonArray("url_list")
                if (videoCovers != null && videoCovers.size() > 0) {
                    coverUrl = videoCovers[0].asString
                }
            }
            if (durationMs == 0L) {
                durationMs = (videoObj?.get("duration")?.asLong ?: 0L)
            }
        }

        if (audioUrl.isNullOrEmpty()) return null

        val title = if (!musicTitle.isNullOrEmpty() && musicTitle != "抖音原声") {
            musicTitle
        } else {
            desc.take(30)
        }
        val artistName = if (!musicAuthor.isNullOrEmpty()) musicAuthor else author

        return Song(
            id = "dy_$awemeId",
            title = title,
            artist = artistName,
            coverUrl = coverUrl,
            audioUrl = audioUrl,
            durationMs = durationMs,
            source = sourceName
        )
    }

    private fun parseViaPublicGateway(rawUrl: String): Song? {
        val gateways = listOf(
            "https://api.pearktrue.cn/api/douyin/?url=",
            "https://tenapi.cn/v2/video?url="
        )

        for (gateway in gateways) {
            try {
                val req = Request.Builder()
                    .url(gateway + rawUrl)
                    .header("User-Agent", desktopUserAgent)
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val root = JsonParser.parseString(body).asJsonObject

                    val data = root.getAsJsonObject("data") ?: root
                    val title = data.get("title")?.asString ?: data.get("desc")?.asString ?: "抖音分享音频"
                    val author = data.get("author")?.asString ?: "抖音创作者"
                    val cover = data.get("cover")?.asString ?: ""
                    val musicUrl = data.get("music")?.asString
                    val videoUrl = data.get("url")?.asString ?: data.get("video")?.asString

                    val playUrl = if (!musicUrl.isNullOrEmpty()) musicUrl else videoUrl
                    if (!playUrl.isNullOrEmpty()) {
                        return Song(
                            id = "dy_gw_${System.currentTimeMillis()}",
                            title = title,
                            artist = author,
                            coverUrl = cover,
                            audioUrl = playUrl,
                            durationMs = 0L,
                            source = sourceName
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore and try next gateway
            }
        }
        return null
    }

    override suspend fun search(query: String): List<Song> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (canHandleLink(trimmed)) {
            val song = parseShareLink(trimmed)
            return@withContext if (song != null) listOf(song) else emptyList()
        }
        return@withContext emptyList()
    }
}
