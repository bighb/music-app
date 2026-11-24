package com.personal.musicapp.data.source

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single owner of every HTTP timeout. A source's callTimeout IS its search deadline:
 * the deadline belongs to the source instead of being imposed from outside, because
 * coroutine cancellation cannot interrupt a blocked socket call.
 */
object HttpPolicy {

    const val KUWO = "酷我"
    const val NETEASE = "网易云"
    const val YOUTUBE = "YouTube"

    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    const val YOUTUBE_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    const val KUWO_SEARCH_MS = 5_000L
    const val NETEASE_SEARCH_MS = 6_000L
    const val YOUTUBE_SEARCH_MS = 8_000L

    const val KUWO_CONNECT_MS = 2_500L
    const val KUWO_READ_MS = 3_500L
    const val NETEASE_CONNECT_MS = 2_500L
    const val NETEASE_READ_MS = 4_000L
    const val YOUTUBE_CONNECT_MS = 3_000L
    const val YOUTUBE_READ_MS = 6_000L

    const val CONVERT_MS = 4_000L
    const val NETEASE_REDIRECT_MS = 4_000L
    const val YOUTUBE_BOOTSTRAP_MS = 3_000L
    const val YOUTUBE_PLAYER_MS = 12_000L
    const val COVER_MS = 1_200L
    const val RESOLVE_TOTAL_DEADLINE_MS = 10_000L

    /** Sources whose answer is required before "nothing found" may be concluded. */
    val settledSources: Set<String> = setOf(KUWO, NETEASE)

    /** Diagnostic A/B hook (100 = production budgets). Never surfaced in the UI. */
    @Volatile
    var scalePercent: Int = 100
        set(value) {
            field = value.coerceIn(25, 300)
        }

    private fun scaled(ms: Long): Long = (ms * scalePercent / 100).coerceAtLeast(50L)

    fun searchDeadlineMs(sourceName: String): Long = scaled(
        when (sourceName) {
            KUWO -> KUWO_SEARCH_MS
            NETEASE -> NETEASE_SEARCH_MS
            else -> YOUTUBE_SEARCH_MS
        }
    )

    val shared: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
        .build()

    fun searchClient(sourceName: String): OkHttpClient {
        val (connect, read) = when (sourceName) {
            KUWO -> KUWO_CONNECT_MS to KUWO_READ_MS
            NETEASE -> NETEASE_CONNECT_MS to NETEASE_READ_MS
            else -> YOUTUBE_CONNECT_MS to YOUTUBE_READ_MS
        }
        return baseClient()
            .connectTimeout(scaled(connect), TimeUnit.MILLISECONDS)
            .readTimeout(scaled(read), TimeUnit.MILLISECONDS)
            .writeTimeout(scaled(read), TimeUnit.MILLISECONDS)
            .callTimeout(scaled(searchDeadlineMs(sourceName)), TimeUnit.MILLISECONDS)
            .build()
    }

    /** Redirect probe for Netease outer URLs and Kuwo anti.s; never follows redirects. */
    fun resolveClient(callTimeoutMs: Long = CONVERT_MS): OkHttpClient {
        return baseClient()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(scaled(2_000L), TimeUnit.MILLISECONDS)
            .readTimeout(scaled(2_500L), TimeUnit.MILLISECONDS)
            .writeTimeout(scaled(2_500L), TimeUnit.MILLISECONDS)
            .callTimeout(scaled(callTimeoutMs), TimeUnit.MILLISECONDS)
            .build()
    }

    fun playerClient(): OkHttpClient {
        return baseClient()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(scaled(YOUTUBE_CONNECT_MS), TimeUnit.MILLISECONDS)
            .readTimeout(scaled(YOUTUBE_READ_MS), TimeUnit.MILLISECONDS)
            .writeTimeout(scaled(YOUTUBE_READ_MS), TimeUnit.MILLISECONDS)
            .callTimeout(scaled(YOUTUBE_PLAYER_MS), TimeUnit.MILLISECONDS)
            .build()
    }

    fun coverClient(): OkHttpClient = resolveClient(COVER_MS).newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun baseClient(): OkHttpClient.Builder = shared.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)

    /** Stream URLs minted for the YouTube client are rejected under any other UA. */
    fun userAgentFor(url: String): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return DESKTOP_UA
        return if (host.endsWith("googlevideo.com") || host.endsWith("youtube.com") ||
            host.endsWith("ytimg.com") || host.endsWith("youtu.be")
        ) {
            YOUTUBE_USER_AGENT
        } else {
            DESKTOP_UA
        }
    }

    /** Signed CDN links must not reach the exported debug log. */
    fun redact(url: String): String {
        return runCatching {
            val uri = java.net.URI(url)
            val path = uri.rawPath.orEmpty()
            (uri.host.orEmpty() + path).take(64)
        }.getOrDefault(url.take(32))
    }
}
