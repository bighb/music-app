package com.personal.musicapp.data.source

import com.personal.musicapp.data.model.Song
import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InterruptedIOException

enum class SourceStatus {
    OK,
    /** HTTP 200 and a well-formed body whose result array was genuinely absent or empty. */
    EMPTY,
    /** The source did not answer inside its own budget: our network, retryable. */
    TIMEOUT,
    HTTP_ERROR,
    PARSE_ERROR,
    ERROR,
    PLAYABILITY_BLOCKED,
    /** The coroutine backstop fired: we blocked outside HTTP, which is a bug in this app. */
    HANG
}

data class SourceOutcome(
    val source: String,
    val status: SourceStatus,
    val count: Int = 0,
    val elapsedMs: Long = 0,
    val httpCode: Int = 0,
    val url: String? = null,
    val detail: String? = null,
    val songs: List<Song> = emptyList()
) {
    val degraded: Boolean get() = status != SourceStatus.OK && status != SourceStatus.EMPTY
}

data class SearchBatch(
    val songs: List<Song>,
    val outcomes: List<SourceOutcome>,
    val settled: Boolean,
    val cached: Boolean = false
)

/** Both domestic sources answered; YouTube's longer budget never delays the "nothing found" verdict. */
fun List<SourceOutcome>.searchSettled(): Boolean =
    HttpPolicy.settledSources.all { name -> any { it.source == name } }

/** Filled in by a source while it runs, so the runner can tell "why zero". */
class HttpReporter {
    @Volatile
    var httpCode: Int = 0

    @Volatile
    var url: String? = null

    @Volatile
    var detail: String? = null
}

class HttpCodeException(val code: Int, val url: String? = null) : IOException("HTTP $code")

object SourceCall {

    private const val HANG_GRACE_MS = 750L

    suspend fun run(source: AudioSource, query: String): SourceOutcome {
        val reporter = HttpReporter()
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000

        fun done(status: SourceStatus, count: Int = 0, detail: String? = null): SourceOutcome {
            val explicitDetail = detail ?: reporter.detail
            return SourceOutcome(
                source = source.sourceName,
                status = status,
                count = count,
                elapsedMs = elapsed(),
                httpCode = reporter.httpCode,
                url = reporter.url,
                detail = explicitDetail
            )
        }

        val songs: List<Song>? = try {
            withTimeoutOrNull(source.searchDeadlineMs + HANG_GRACE_MS) {
                source.search(query, reporter)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedIOException) {
            return done(SourceStatus.TIMEOUT, detail = e.javaClass.simpleName)
        } catch (e: HttpCodeException) {
            if (reporter.httpCode == 0) reporter.httpCode = e.code
            if (reporter.url == null) reporter.url = e.url
            return done(SourceStatus.HTTP_ERROR, detail = "HTTP ${e.code}")
        } catch (e: JsonParseException) {
            return done(SourceStatus.PARSE_ERROR, detail = e.javaClass.simpleName)
        } catch (e: IllegalStateException) {
            return done(SourceStatus.PARSE_ERROR, detail = e.javaClass.simpleName)
        } catch (e: Exception) {
            return done(SourceStatus.ERROR, detail = e.javaClass.simpleName)
        }

        return when {
            songs == null -> done(SourceStatus.HANG)
            songs.isEmpty() -> done(SourceStatus.EMPTY)
            else -> done(SourceStatus.OK, count = songs.size).copy(songs = songs)
        }
    }

    fun classify(e: Throwable?): SourceStatus = when (e) {
        is InterruptedIOException -> SourceStatus.TIMEOUT
        is HttpCodeException -> SourceStatus.HTTP_ERROR
        is JsonParseException -> SourceStatus.PARSE_ERROR
        is IllegalStateException -> SourceStatus.PARSE_ERROR
        null -> SourceStatus.ERROR
        else -> SourceStatus.ERROR
    }
}
