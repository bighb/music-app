package com.personal.musicapp.data.source

import com.personal.musicapp.data.model.Song
import com.personal.musicapp.util.AppLogger
import com.personal.musicapp.util.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap

/** Why a song could not be played. Drives both the retry policy and the user-facing wording. */
enum class FailureKind { NONE, SOURCE_UNAVAILABLE, NO_FREE_SOURCE, PROMO_REJECTED }

data class ResolveAttempt(
    val songId: String,
    val source: String,
    val candidate: String,
    val httpCode: Int = 0,
    val elapsedMs: Long = 0,
    val outcome: String
)

data class ResolvedStream(val url: String, val sourceUsed: String, val fromCache: Boolean = false)

data class ResolveResult(
    val stream: ResolvedStream?,
    val failure: FailureKind,
    val attempts: List<ResolveAttempt>
) {
    /** Per-source failure summary, e.g. "酷我 超时 · 网易 HTTP 403". */
    fun describeFailures(): String {
        val seen = LinkedHashSet<String>()
        for (a in attempts) {
            if (a.outcome == "ok") continue
            seen.add("${a.source} ${outcomeLabel(a)}")
        }
        return seen.take(2).joinToString(" · ")
    }

    private fun outcomeLabel(a: ResolveAttempt): String = when (a.outcome) {
        "timeout" -> "超时"
        "http_error" -> "HTTP ${a.httpCode}"
        "blocked" -> "版权限制"
        "promo" -> "试听片段"
        else -> "无免费音频"
    }
}

data class HttpExchange(val code: Int, val body: String, val location: String?)

/** One GET that does not follow redirects. Hand-written fakes replace it in unit tests. */
interface ResolverHttp {
    suspend fun exchange(url: String, headers: Map<String, String> = emptyMap()): HttpExchange
}

class OkHttpResolverHttp : ResolverHttp {
    private val client by lazy { HttpPolicy.resolveClient() }

    override suspend fun exchange(url: String, headers: Map<String, String>): HttpExchange {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).awaitResponse().use { resp ->
            // Never drain the body: a direct link may answer 200 with the whole audio file.
            val body = resp.peekBody(2048).string()
            return HttpExchange(resp.code, body, resp.header("Location"))
        }
    }
}

/** Thrown to ExoPlayer's loader when no candidate yielded a stream. Carries no CDN URL. */
class StreamResolveException(val kind: FailureKind, val detail: String) : IOException(detail)

/**
 * The only implementation of "song -> playable stream URL"; playback and download both come
 * here. Candidates run in a fixed order under one total deadline, and a rejected candidate
 * (placeholder, 404, promo) does not end the search: the chain continues.
 */
class StreamResolver(
    private val http: ResolverHttp,
    private val resolveYouTube: suspend (String) -> YtResolveResult,
    private val findNeteaseByTitle: suspend (String, String) -> Song?,
    private val findKuwoUrlByTitle: suspend (String, String) -> String?,
    private val alternatives: (Song) -> List<Song> = { emptyList() },
    private val totalDeadlineMs: Long = HttpPolicy.RESOLVE_TOTAL_DEADLINE_MS,
    private val ttlMs: Long = URL_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        const val URL_TTL_MS = 5 * 60 * 1000L
        private const val TAG = "StreamResolver"
        private const val BLACKLIST_MAX = 200
        private const val USED_KUWO = "kuwo"
        private const val USED_NETEASE = "netease"
        private const val USED_YOUTUBE = "youtube"
        private const val USED_DIRECT = "direct"

        /** Our network or the source misbehaved: retrying can help. */
        private val hardOutcomes = setOf("timeout", "http_error", "error")

        /** The source answered and has no free stream: retrying the same candidate cannot help. */
        private val definitiveOutcomes = setOf("empty", "promo", "blocked")

        private val neteaseIdPattern = Regex("[?&]id=(\\d+)")

        fun isNeteaseOuterUrl(url: String): Boolean = url.contains("music.163.com/song/media/outer/url")

        fun isNotFoundRedirect(location: String): Boolean =
            location.contains("/404") || location.contains("404.html")
    }

    private enum class Verdict { OK, EMPTY, PROMO, TIMEOUT, HTTP_ERROR, BLOCKED, ERROR }

    private class Hit(val url: String?, val verdict: Verdict, val httpCode: Int = 0)

    private class Found(val url: String, val used: String)

    private class Candidate(
        val key: String,
        val source: String,
        val used: String,
        val action: suspend (tried: Set<String>) -> Hit
    )

    private class CachedUrl(val url: String, val at: Long)

    private val cache = ConcurrentHashMap<String, CachedUrl>()
    private val resolvedUrls = ConcurrentHashMap<String, String>()
    private val blacklist = LinkedHashSet<String>()
    private val songLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(songId: String): Mutex = songLocks.computeIfAbsent(songId) { Mutex() }

    /** Fresh cached URL for [songId], or null (expired entries are dropped). */
    fun peek(songId: String): String? {
        val entry = cache[songId] ?: return null
        if (clock() - entry.at < ttlMs) return entry.url
        cache.remove(songId)
        return null
    }

    fun evict(songId: String) {
        cache.remove(songId)
        resolvedUrls.remove(songId)
    }

    /** The player found that [url] is a placeholder: never serve it again, force a re-resolve. */
    fun reject(songId: String, url: String) {
        synchronized(blacklist) {
            blacklist.remove(url)
            blacklist.add(url)
            while (blacklist.size > BLACKLIST_MAX) {
                val it = blacklist.iterator()
                it.next()
                it.remove()
            }
        }
        evict(songId)
    }

    /** URL last handed to the player for this song, so a placeholder finding can be traced back. */
    fun resolvedUrlFor(songId: String): String? = resolvedUrls[songId]

    suspend fun resolve(song: Song): ResolveResult = withContext(Dispatchers.IO) {
        lockFor(song.id).withLock { resolveLocked(song) }
    }

    private suspend fun resolveLocked(song: Song): ResolveResult {
        peek(song.id)?.let { cached ->
            resolvedUrls[song.id] = cached
            return ResolveResult(ResolvedStream(cached, "cache", fromCache = true), FailureKind.NONE, emptyList())
        }

        val raw = song.audioUrl
        if (!isResolvable(raw)) {
            return ResolveResult(ResolvedStream(raw, USED_DIRECT), FailureKind.NONE, emptyList())
        }

        val started = clock()
        val attempts = ArrayList<ResolveAttempt>()
        var passStart = 0
        var skipKeys = emptySet<String>()

        // At most two passes: one whole-chain retry on a network outage, same total budget.
        for (pass in 0..1) {
            val found = runChain(song, started, attempts, skipKeys)
            if (found != null) {
                cache[song.id] = CachedUrl(found.url, clock())
                resolvedUrls[song.id] = found.url
                return ResolveResult(ResolvedStream(found.url, found.used), FailureKind.NONE, attempts)
            }

            val ledger = attempts.subList(passStart, attempts.size).toList()
            val failure = classify(ledger)
            val elapsed = clock() - started
            val retryWorthwhile = pass == 0 &&
                failure == FailureKind.SOURCE_UNAVAILABLE &&
                elapsed < totalDeadlineMs / 2 &&
                attempts.any { it.outcome in hardOutcomes }
            if (retryWorthwhile) {
                AppLogger.v(TAG, "[RESL] ${song.id} retrying chain after outage (${elapsed}ms used)")
                skipKeys = attempts.filter { it.outcome in definitiveOutcomes }.map { it.candidate }.toSet()
                passStart = attempts.size
                continue
            }
            evict(song.id)
            return ResolveResult(null, failure, attempts)
        }
        evict(song.id)
        return ResolveResult(null, FailureKind.NO_FREE_SOURCE, attempts)
    }

    private fun classify(ledger: List<ResolveAttempt>): FailureKind {
        if (ledger.any { it.outcome in hardOutcomes }) return FailureKind.SOURCE_UNAVAILABLE
        if (ledger.any { it.outcome == "promo" }) return FailureKind.PROMO_REJECTED
        return FailureKind.NO_FREE_SOURCE
    }

    private fun isResolvable(rawUrl: String): Boolean = rawUrl.startsWith("http://") ||
        rawUrl.startsWith("https://") || rawUrl.startsWith("kuwo://") || rawUrl.startsWith("youtube://")

    private fun isPlaceholder(url: String): Boolean {
        if (PlaceholderGate.isPromo(url)) return true
        return synchronized(blacklist) { url in blacklist }
    }

    private fun verdictToken(verdict: Verdict): String =
        if (verdict == Verdict.OK) "ok" else verdict.name.lowercase()

    private suspend fun runChain(
        song: Song,
        started: Long,
        attempts: MutableList<ResolveAttempt>,
        skipKeys: Set<String>
    ): Found? {
        val tried = HashSet<String>()
        for (candidate in buildCandidates(song)) {
            if (!tried.add(candidate.key) || candidate.key in skipKeys) continue

            val remaining = totalDeadlineMs - (clock() - started)
            val candidateStart = clock()
            val hit = try {
                withTimeoutOrNull(remaining) { candidate.action(tried) } ?: Hit(null, Verdict.TIMEOUT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedIOException) {
                Hit(null, Verdict.TIMEOUT)
            } catch (e: Exception) {
                Hit(null, Verdict.ERROR)
            }

            attempts.add(
                ResolveAttempt(
                    songId = song.id,
                    source = candidate.source,
                    candidate = candidate.key,
                    httpCode = hit.httpCode,
                    elapsedMs = clock() - candidateStart,
                    outcome = verdictToken(hit.verdict)
                )
            )
            hit.url?.let { return Found(it, candidate.used) }
            if (clock() - started >= totalDeadlineMs) break
        }
        return null
    }

    /** Own source first, then the cross-index, then title searches, then YouTube alternates. */
    private fun buildCandidates(song: Song): List<Candidate> {
        val list = ArrayList<Candidate>()
        val alts = try {
            alternatives(song)
        } catch (_: Exception) {
            emptyList()
        }

        for (target in listOf(song) + alts.filterNot { isYouTubeSource(it) }) {
            val raw = target.audioUrl
            when {
                raw.startsWith("kuwo://") -> {
                    val rid = raw.removePrefix("kuwo://")
                    list.add(Candidate("kuwo:$rid", HttpPolicy.KUWO, USED_KUWO) { _ -> tryKuwo(rid) })
                }
                raw.startsWith("youtube://") -> {
                    val videoId = raw.removePrefix("youtube://")
                    list.add(Candidate("youtube:$videoId", HttpPolicy.YOUTUBE, USED_YOUTUBE) { _ -> tryYouTube(videoId) })
                }
                isNeteaseOuterUrl(raw) -> {
                    val id = neteaseIdPattern.find(raw)?.groupValues?.get(1) ?: continue
                    list.add(Candidate("netease:$id", HttpPolicy.NETEASE, USED_NETEASE) { _ -> tryNetease(id) })
                }
                raw.startsWith("http://") || raw.startsWith("https://") -> {
                    list.add(Candidate("direct:$raw", target.source, USED_DIRECT) { _ -> tryDirect(raw) })
                }
            }
        }

        list.add(Candidate("netease-title", HttpPolicy.NETEASE, USED_NETEASE) { tried -> tryNeteaseByTitle(song, tried) })
        list.add(Candidate("kuwo-title", HttpPolicy.KUWO, USED_KUWO) { _ -> tryKuwoByTitle(song) })

        for (target in alts.filter { isYouTubeSource(it) }) {
            val videoId = target.audioUrl.removePrefix("youtube://")
            list.add(Candidate("youtube:$videoId", HttpPolicy.YOUTUBE, USED_YOUTUBE) { _ -> tryYouTube(videoId) })
        }
        return list
    }

    private fun isYouTubeSource(target: Song): Boolean =
        target.audioUrl.startsWith("youtube://") || target.source.equals(HttpPolicy.YOUTUBE, ignoreCase = true)

    private suspend fun guarded(block: suspend () -> Hit): Hit = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: InterruptedIOException) {
        Hit(null, Verdict.TIMEOUT)
    } catch (e: Exception) {
        Hit(null, Verdict.ERROR)
    }

    private suspend fun tryKuwo(rid: String): Hit = guarded {
        val ex = http.exchange(KuwoAudioHelper.antiServerUrl(rid))
        val body = ex.body.trim()
        when {
            ex.code >= 400 -> Hit(null, Verdict.HTTP_ERROR, ex.code)
            !body.startsWith("http") -> Hit(null, Verdict.EMPTY, ex.code)
            isPlaceholder(body) -> Hit(null, Verdict.PROMO, ex.code)
            else -> Hit(body, Verdict.OK, ex.code)
        }
    }

    private suspend fun tryNetease(id: String): Hit = guarded {
        val outer = NetMusicAudioSource.outerUrl(id.toLong())
        val ex = http.exchange(outer, mapOf("User-Agent" to HttpPolicy.userAgentFor(outer)))
        val location = ex.location.orEmpty()
        when {
            ex.code >= 400 -> Hit(null, Verdict.HTTP_ERROR, ex.code)
            isNotFoundRedirect(location) || location.isEmpty() -> Hit(null, Verdict.EMPTY, ex.code)
            isPlaceholder(location) -> Hit(null, Verdict.PROMO, ex.code)
            else -> Hit(location.replace("http://", "https://"), Verdict.OK, ex.code)
        }
    }

    private suspend fun tryYouTube(videoId: String): Hit = guarded {
        val r = resolveYouTube(videoId)
        val url = r.url
        if (url != null) {
            Hit(url, Verdict.OK, r.httpCode)
        } else {
            Hit(
                null,
                when (r.status) {
                    SourceStatus.PLAYABILITY_BLOCKED -> Verdict.BLOCKED
                    SourceStatus.TIMEOUT, SourceStatus.HANG -> Verdict.TIMEOUT
                    SourceStatus.EMPTY -> Verdict.EMPTY
                    else -> Verdict.ERROR
                },
                r.httpCode
            )
        }
    }

    /** A direct http link (e.g. from a share parse) is validated once instead of being trusted. */
    private suspend fun tryDirect(url: String): Hit = guarded {
        val ex = http.exchange(url, mapOf("User-Agent" to HttpPolicy.DESKTOP_UA, "Range" to "bytes=0-0"))
        val location = ex.location.orEmpty()
        when {
            ex.code >= 400 -> Hit(null, Verdict.HTTP_ERROR, ex.code)
            isNotFoundRedirect(location) -> Hit(null, Verdict.EMPTY, ex.code)
            else -> Hit(url, Verdict.OK, ex.code)
        }
    }

    private suspend fun tryNeteaseByTitle(song: Song, tried: Set<String>): Hit = guarded {
        val found = findNeteaseByTitle(song.title, song.artist)
        if (found == null) {
            Hit(null, Verdict.EMPTY)
        } else {
            val id = neteaseIdPattern.find(found.audioUrl)?.groupValues?.get(1)
            if (id == null || "netease:$id" in tried) Hit(null, Verdict.EMPTY) else tryNetease(id)
        }
    }

    private suspend fun tryKuwoByTitle(song: Song): Hit = guarded {
        val url = findKuwoUrlByTitle(song.title, song.artist)
        when {
            url == null -> Hit(null, Verdict.EMPTY)
            isPlaceholder(url) -> Hit(null, Verdict.PROMO)
            else -> Hit(url, Verdict.OK)
        }
    }
}
