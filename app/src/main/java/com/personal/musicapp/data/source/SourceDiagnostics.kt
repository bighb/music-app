package com.personal.musicapp.data.source

import com.personal.musicapp.util.AppLogger

/**
 * Single formatting exit for source diagnostics. Fixed token order so one grep aggregates
 * searches, resolves and placeholder rejections alike.
 */
object SourceDiagnostics {

    private const val TAG = "SourceDiag"

    private val shortNames = mapOf(
        HttpPolicy.KUWO to "kw",
        HttpPolicy.NETEASE to "net",
        HttpPolicy.YOUTUBE to "yt"
    )

    fun shortName(source: String): String = shortNames[source] ?: source.take(4).lowercase()

    fun statusToken(status: SourceStatus): String = status.name.lowercase().replace('_', '-')

    /** Null when the source answered fine (including a genuine zero). */
    fun problemToken(outcome: SourceOutcome): String? = when (outcome.status) {
        SourceStatus.OK, SourceStatus.EMPTY -> null
        SourceStatus.TIMEOUT, SourceStatus.HANG -> "timeout"
        SourceStatus.HTTP_ERROR -> "http=${outcome.httpCode}"
        else -> statusToken(outcome.status)
    }

    fun sourceLine(outcome: SourceOutcome, query: String): String {
        val sb = StringBuilder("[SRC]  ")
        sb.append(shortName(outcome.source).padEnd(5))
        sb.append("q=\"").append(query.replace("\n", " ")).append("\" ")
        sb.append("http=").append(if (outcome.httpCode == 0) "-" else outcome.httpCode).append(' ')
        sb.append(outcome.elapsedMs).append("ms ")
        sb.append("n=").append(outcome.count).append(' ')
        sb.append("status=").append(statusToken(outcome.status))
        outcome.url?.let { sb.append(" url=").append(it) }
        outcome.detail?.let { sb.append(" detail=").append(it) }
        return sb.toString()
    }

    fun logSource(outcome: SourceOutcome, query: String) {
        when (outcome.status) {
            SourceStatus.OK, SourceStatus.EMPTY -> AppLogger.v(TAG, sourceLine(outcome, query))
            SourceStatus.TIMEOUT, SourceStatus.HANG -> AppLogger.v(TAG, sourceLine(outcome, query))
            else -> AppLogger.w(TAG, sourceLine(outcome, query))
        }
    }

    fun logSearch(
        query: String,
        elapsedMs: Long,
        outcomes: List<SourceOutcome>,
        cached: Boolean,
        merged: Int,
        songs: Int,
        settled: Boolean
    ) {
        val ok = outcomes.count { it.status == SourceStatus.OK }
        val empty = outcomes.count { it.status == SourceStatus.EMPTY }
        val degraded = outcomes.count { it.degraded }
        val line = "[SRCH] \"${query.replace("\n", " ")}\" ${elapsedMs / 1000.0}s ok=$ok/${outcomes.size} " +
            "empty=$empty degraded=$degraded cached=$cached merged=$merged songs=$songs settled=$settled"
        if (degraded > 0) AppLogger.w(TAG, line) else AppLogger.v(TAG, line)
    }

    fun logResolve(result: ResolveResult, songId: String, title: String, source: String) {
        val stream = result.stream
        if (stream != null) {
            AppLogger.v(
                TAG,
                "[RESL] $songId $source→${stream.sourceUsed} cached=${stream.fromCache} ${result.attempts.size}req " +
                    "url=${HttpPolicy.redact(stream.url)}"
            )
            return
        }
        val ledger = result.attempts.joinToString(prefix = "[", postfix = "]") {
            "${shortName(it.source)}:http=${if (it.httpCode == 0) "-" else it.httpCode}:${it.elapsedMs}ms:${it.outcome}"
        }
        val line = "[RESL] $songId FAIL failure=${result.failure.name.lowercase().replace('_', '-')} " +
            "req=${result.attempts.size} ledger=$ledger 《$title》"
        if (result.failure == FailureKind.SOURCE_UNAVAILABLE) AppLogger.w(TAG, line) else AppLogger.v(TAG, line)
    }

    fun logReject(songId: String, reason: String, estimateMs: Long, expectedMs: Long, next: String) {
        AppLogger.w(
            TAG,
            "[REJ ] $songId placeholder=$reason est=${estimateMs / 1000}s expect=${expectedMs / 1000}s next=$next"
        )
    }
}
