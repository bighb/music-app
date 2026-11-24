package com.personal.musicapp

import com.personal.musicapp.data.model.Song
import com.personal.musicapp.data.source.HttpExchange
import com.personal.musicapp.data.source.HttpPolicy
import com.personal.musicapp.data.source.NetMusicAudioSource
import com.personal.musicapp.data.source.ResolveResult
import com.personal.musicapp.data.source.ResolverHttp
import com.personal.musicapp.data.source.SourceStatus
import com.personal.musicapp.data.source.StreamResolver
import com.personal.musicapp.data.source.YtResolveResult
import com.personal.musicapp.data.source.FailureKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** The production resolver driven by a recording fake for HTTP: no network, exact request order. */
class StreamResolverTest {

    private class Rig {
        var now = 0L
        val log = ArrayList<String>()
        var reply: (String) -> HttpExchange = { HttpExchange(200, "", null) }
        var replyCostMs = 0L
        var youtube: suspend (String) -> YtResolveResult = { YtResolveResult(null, status = SourceStatus.EMPTY) }
        var neteaseByTitle: suspend (String, String) -> Song? = { _, _ -> null }
        var kuwoByTitle: suspend (String, String) -> String? = { _, _ -> null }
        var alts: (Song) -> List<Song> = { emptyList() }

        val http = object : ResolverHttp {
            override suspend fun exchange(url: String, headers: Map<String, String>): HttpExchange {
                log.add(label(url))
                now += replyCostMs
                return reply(url)
            }
        }

        val httpCount: Int get() = log.count { !it.startsWith("title:") && !it.startsWith("youtube:") }

        fun resolver(): StreamResolver = StreamResolver(
            http = http,
            resolveYouTube = { id -> log.add("youtube:$id"); youtube(id) },
            findNeteaseByTitle = { t, a -> log.add("title:netease"); neteaseByTitle(t, a) },
            findKuwoUrlByTitle = { t, a -> log.add("title:kuwo"); kuwoByTitle(t, a) },
            alternatives = alts,
            clock = { now }
        )

        companion object {
            fun label(url: String): String {
                val id = Regex("[?&](?:rid|id)=(\\d+)").find(url)?.groupValues?.get(1)
                return when {
                    url.startsWith("http://antiserver.kuwo.cn/") -> "kuwo:$id"
                    url.startsWith("https://music.163.com/song/media/outer/url") -> "netease:$id"
                    else -> "direct"
                }
            }
        }
    }

    private fun song(id: String, url: String, source: String, title: String = "晴天", artist: String = "周杰伦") =
        Song(id = id, title = title, artist = artist, audioUrl = url, durationMs = 240_000L, source = source)

    private fun kw(id: String = "111") = song("kw_$id", "kuwo://$id", HttpPolicy.KUWO)
    private fun net(id: String = "222") = song("net_$id", NetMusicAudioSource.outerUrl(id.toLong()), HttpPolicy.NETEASE)
    private fun yt(id: String = "vid11chars") = song("yt_$id", "youtube://$id", HttpPolicy.YOUTUBE)

    private val okStream = "https://x.kuwo.cn/star/a.mp3"
    private fun ok(url: String = okStream) = HttpExchange(200, url, null)
    private fun redirect(location: String) = HttpExchange(302, "", location)
    private fun notFound(url: String): HttpExchange =
        if (url.startsWith("https://music.163.com")) redirect("https://music.163.com/404") else HttpExchange(200, "", null)

    private fun outcomes(result: ResolveResult) = result.attempts.map { it.outcome }

    // ---------- candidate order ----------

    @Test
    fun ownSourceIsTriedFirstAndOneRequestSuffices() = runBlocking {
        val rig = Rig().apply { reply = { ok() } }
        val result = rig.resolver().resolve(kw())
        assertNotNull(result.stream)
        assertEquals(okStream, result.stream!!.url)
        assertEquals("kuwo", result.stream!!.sourceUsed)
        assertEquals(FailureKind.NONE, result.failure)
        assertEquals(listOf("kuwo:111"), rig.log)
    }

    @Test
    fun kuwoSongRunsOwnIdThenCrossIndexThenTitleSearchesThenYoutubeLast() = runBlocking {
        val rig = Rig().apply {
            reply = { url -> notFound(url) }
            neteaseByTitle = { _, _ -> net("333") }
            youtube = { YtResolveResult("https://rr.googlevideo.com/a", 200, SourceStatus.OK) }
            alts = { listOf(net("222"), yt()) }
        }
        val result = rig.resolver().resolve(kw())

        assertEquals(
            listOf("kuwo:111", "netease:222", "title:netease", "netease:333", "title:kuwo", "youtube:vid11chars"),
            rig.log
        )
        assertEquals("youtube", result.stream!!.sourceUsed)
    }

    @Test
    fun neteaseSongRunsOuterUrlBeforeTheKuwoCrossIndex() = runBlocking {
        val rig = Rig().apply { alts = { listOf(kw("111")) } }
        val result = rig.resolver().resolve(net("222"))
        assertEquals(listOf("netease:222", "kuwo:111", "title:netease", "title:kuwo"), rig.log)
        assertNull(result.stream)
    }

    @Test
    fun youtubeSongResolvesWithoutAnyProbeRequest() = runBlocking {
        val rig = Rig().apply { youtube = { YtResolveResult("https://rr.googlevideo.com/a", 200, SourceStatus.OK) } }
        val result = rig.resolver().resolve(yt())
        assertEquals(listOf("youtube:vid11chars"), rig.log)
        assertEquals(0, rig.httpCount)
        assertEquals(1, result.attempts.size)
    }

    @Test
    fun playabilityBlockedIsRecordedAndTheChainContinues() = runBlocking {
        val rig = Rig().apply {
            youtube = { YtResolveResult(null, 200, SourceStatus.PLAYABILITY_BLOCKED, "UNPLAYABLE") }
            kuwoByTitle = { _, _ -> okStream }
        }
        val result = rig.resolver().resolve(yt())
        assertEquals(listOf("youtube:vid11chars", "title:netease", "title:kuwo"), rig.log)
        assertEquals("blocked", result.attempts.first().outcome)
        assertEquals("kuwo", result.stream!!.sourceUsed)
    }

    @Test
    fun aDirectHttpLinkIsValidatedExactlyOnce() = runBlocking {
        val url = "https://cdn.example.com/share/a.mp3"
        val accepted = Rig().apply { reply = { HttpExchange(206, "", null) } }
        val okResult = accepted.resolver().resolve(song("dy_1", url, "抖音原声"))
        assertEquals(url, okResult.stream!!.url)
        assertEquals("direct", okResult.stream!!.sourceUsed)
        assertEquals(listOf("direct"), accepted.log)

        val gone = Rig().apply { reply = { redirect("https://cdn.example.com/404.html") } }
        assertNull(gone.resolver().resolve(song("dy_1", url, "抖音原声")).stream)
        assertEquals(1, gone.httpCount)
    }

    @Test
    fun localFilesAndUnknownSchemesAreNotResolved() = runBlocking {
        val rig = Rig()
        val result = rig.resolver().resolve(song("local_1", "file:///storage/a.mp3", "本地"))
        assertEquals("file:///storage/a.mp3", result.stream!!.url)
        assertTrue(rig.log.isEmpty())
    }

    // ---------- a rejected candidate continues the chain ----------

    @Test
    fun aPromoClipDoesNotEndTheSearch() = runBlocking {
        val rig = Rig().apply {
            reply = { url ->
                if (url.startsWith("http://antiserver")) HttpExchange(200, "http://x.kuwo.cn/588957081.mp3", null)
                else redirect("http://m704.music.126.net/2024/a.mp3")
            }
            alts = { listOf(net("222")) }
        }
        val result = rig.resolver().resolve(kw())
        assertEquals("https://m704.music.126.net/2024/a.mp3", result.stream!!.url)
        assertEquals("netease", result.stream!!.sourceUsed)
        assertEquals(listOf("promo", "ok"), outcomes(result))
        assertEquals(2, rig.httpCount)
    }

    @Test
    fun notFoundRedirectsAndMissingLocationAreRejected() = runBlocking {
        for (location in listOf("https://music.163.com/404", "https://music.163.com/404.html", "")) {
            val rig = Rig().apply { reply = { redirect(location) } }
            val result = rig.resolver().resolve(net("222"))
            assertNull("location='$location' must not be played", result.stream)
            assertEquals(FailureKind.NO_FREE_SOURCE, result.failure)
        }
        assertTrue(StreamResolver.isNotFoundRedirect("https://music.163.com/404"))
        assertTrue(StreamResolver.isNotFoundRedirect("https://music.163.com/404.html"))
        assertFalse(StreamResolver.isNotFoundRedirect("https://m704.music.126.net/a.mp3"))
    }

    @Test
    fun theFallbackNeverRunsWithoutABound() = runBlocking {
        val rig = Rig().apply {
            reply = { url -> notFound(url) }
            neteaseByTitle = { _, _ -> net("333") }
            alts = { listOf(net("222"), yt()) }
        }
        val result = rig.resolver().resolve(kw())
        assertNull(result.stream)
        assertEquals("One song used to cost up to 16 serial requests", 3, rig.httpCount)
        assertEquals(6, rig.log.size)
    }

    // ---------- failure classification ----------

    @Test
    fun anOutageIsRetryableEvenWhenOtherCandidatesWerePromo() = runBlocking {
        val rig = Rig().apply {
            reply = { url ->
                if (url.startsWith("http://antiserver")) HttpExchange(200, "http://x.kuwo.cn/588957081.mp3", null)
                else HttpExchange(503, "", null)
            }
            alts = { listOf(net("222")) }
        }
        val result = rig.resolver().resolve(kw())
        assertEquals(FailureKind.SOURCE_UNAVAILABLE, result.failure)
        assertEquals("酷我 试听片段 · 网易云 HTTP 503", result.describeFailures())
    }

    @Test
    fun aTimeoutPlusPromoIsNeverReportedAsPromoRejected() = runBlocking {
        val rig = Rig().apply {
            reply = { url ->
                if (url.startsWith("http://antiserver")) HttpExchange(200, "http://x.kuwo.cn/588957081.mp3", null)
                else throw SocketTimeoutException("timeout")
            }
            alts = { listOf(net("222")) }
        }
        assertEquals(FailureKind.SOURCE_UNAVAILABLE, rig.resolver().resolve(kw()).failure)
    }

    @Test
    fun onlyPromoClipsFoundMeansPromoRejected() = runBlocking {
        val rig = Rig().apply { reply = { HttpExchange(200, "http://x.kuwo.cn/588957081.mp3", null) } }
        assertEquals(FailureKind.PROMO_REJECTED, rig.resolver().resolve(kw()).failure)
    }

    @Test
    fun anHttpErrorIsRetryableWhileAWelledEmptyAnswerIsNot() = runBlocking {
        val httpError = Rig().apply { reply = { url -> if (url.startsWith("http://antiserver")) HttpExchange(403, "", null) else notFound(url) } }
        val result = httpError.resolver().resolve(kw())
        assertEquals(FailureKind.SOURCE_UNAVAILABLE, result.failure)
        assertEquals("酷我 HTTP 403 · 网易云 无免费音频", result.describeFailures())

        val empty = Rig()
        assertEquals(FailureKind.NO_FREE_SOURCE, empty.resolver().resolve(kw()).failure)
    }

    @Test
    fun anOutOfBandExceptionIsAnErrorNotEmpty() = runBlocking {
        val rig = Rig().apply { reply = { throw IOException("connection reset") } }
        val result = rig.resolver().resolve(kw())
        assertEquals("error", result.attempts.first().outcome)
        assertEquals(FailureKind.SOURCE_UNAVAILABLE, result.failure)
    }

    // ---------- budget ----------

    @Test
    fun noCandidateStartsOnceTheDeadlineHasPassed() = runBlocking {
        val rig = Rig().apply {
            replyCostMs = 6_000L
            reply = { throw SocketTimeoutException("timeout") }
            alts = { listOf(net("222"), kw("444")) }
        }
        val result = rig.resolver().resolve(kw())
        assertEquals(FailureKind.SOURCE_UNAVAILABLE, result.failure)
        assertEquals(listOf("kuwo:111", "netease:222"), rig.log)
        assertEquals(listOf("timeout", "timeout"), outcomes(result))
        assertEquals(12_000L, rig.now)
    }

    @Test
    fun aSlowTitleLookupLeavesNoBudgetForTheRestOfTheChain() = runBlocking {
        val rig = Rig().apply {
            replyCostMs = 4_999L
            reply = { throw SocketTimeoutException("timeout") }
            neteaseByTitle = { _, _ -> now += 2L; null }
            alts = { listOf(net("222")) }
        }
        val result = rig.resolver().resolve(kw())
        assertEquals(FailureKind.SOURCE_UNAVAILABLE, result.failure)
        assertEquals(listOf("kuwo:111", "netease:222", "title:netease"), rig.log)
        assertEquals(10_000L, rig.now)
    }

    @Test
    fun anOutageGetsExactlyOneWholeChainRetry() = runBlocking {
        val affordable = Rig().apply {
            replyCostMs = 4_000L
            reply = { throw SocketTimeoutException("timeout") }
        }
        assertEquals(FailureKind.SOURCE_UNAVAILABLE, affordable.resolver().resolve(kw()).failure)
        assertEquals("Half the budget still left: one retry of the chain", 2, affordable.httpCount)

        val exhausted = Rig().apply {
            replyCostMs = 6_000L
            reply = { throw SocketTimeoutException("timeout") }
        }
        exhausted.resolver().resolve(kw())
        assertEquals("More than half the budget gone: no second pass", 1, exhausted.httpCount)
    }

    // ---------- cache ----------

    @Test
    fun aResolvedUrlIsReusedUntilItExpiresOrIsEvicted() = runBlocking {
        val rig = Rig().apply { reply = { ok() } }
        val resolver = rig.resolver()

        assertFalse(resolver.resolve(kw()).stream!!.fromCache)
        val cached = resolver.resolve(kw())
        assertTrue(cached.stream!!.fromCache)
        assertTrue(cached.attempts.isEmpty())
        assertEquals(1, rig.httpCount)

        rig.now = StreamResolver.URL_TTL_MS - 1L
        assertTrue(resolver.resolve(kw()).stream!!.fromCache)
        rig.now = StreamResolver.URL_TTL_MS + 1L
        assertFalse(resolver.resolve(kw()).stream!!.fromCache)
        assertEquals(2, rig.httpCount)

        resolver.evict("kw_111")
        assertNull(resolver.peek("kw_111"))
        assertFalse(resolver.resolve(kw()).stream!!.fromCache)
        assertEquals(3, rig.httpCount)
    }

    @Test
    fun aUrlRejectedAsPlaceholderIsNeverServedAgain() = runBlocking {
        val rig = Rig().apply { reply = { ok(okStream) } }
        val resolver = rig.resolver()
        assertEquals(okStream, resolver.resolve(kw()).stream!!.url)
        assertEquals(okStream, resolver.resolvedUrlFor("kw_111"))

        resolver.reject("kw_111", okStream)
        val after = resolver.resolve(kw())
        assertNull(after.stream)
        assertEquals(listOf("promo", "empty", "empty"), outcomes(after))
    }
}
