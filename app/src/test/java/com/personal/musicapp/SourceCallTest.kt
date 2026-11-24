package com.personal.musicapp

import com.google.gson.JsonSyntaxException
import com.personal.musicapp.data.model.Song
import com.personal.musicapp.data.source.AudioSource
import com.personal.musicapp.data.source.HttpCodeException
import com.personal.musicapp.data.source.HttpReporter
import com.personal.musicapp.data.source.SourceCall
import com.personal.musicapp.data.source.SourceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

class SourceCallTest {

    private class FakeSource(
        private val behavior: suspend (HttpReporter) -> List<Song>,
        private val deadlineMs: Long = 10_000L
    ) : AudioSource {
        override val sourceName = "fake"
        override val searchDeadlineMs get() = deadlineMs
        override suspend fun search(query: String): List<Song> = behavior(HttpReporter())
        override suspend fun search(query: String, reporter: HttpReporter): List<Song> = behavior(reporter)
        override suspend fun parseShareLink(text: String): Song? = null
        override fun canHandleLink(text: String): Boolean = false
    }

    private fun oneSong(id: String = "kw_1") =
        Song(id = id, title = "晴天", artist = "周杰伦", audioUrl = "kuwo://1", source = "酷我")

    private suspend fun runSource(deadlineMs: Long = 10_000L, behavior: suspend (HttpReporter) -> List<Song>) =
        SourceCall.run(FakeSource(behavior, deadlineMs), "晴天")

    @Test
    fun http200WithNoRowsIsAnEmptyAnswerNotAFailure() = runBlocking {
        val outcome = runSource { emptyList() }
        assertEquals(SourceStatus.EMPTY, outcome.status)
        assertFalse(outcome.degraded)
        assertEquals(0, outcome.count)
    }

    @Test
    fun songsArePassedThroughWithTheirCount() = runBlocking {
        val songs = listOf(oneSong(), oneSong("kw_2"))
        val outcome = runSource { songs }
        assertEquals(SourceStatus.OK, outcome.status)
        assertEquals(2, outcome.count)
        assertSame(songs, outcome.songs)
        assertFalse(outcome.degraded)
    }

    @Test
    fun socketTimeoutIsATimeout() = runBlocking {
        val outcome = runSource { throw SocketTimeoutException("timeout") }
        assertEquals(SourceStatus.TIMEOUT, outcome.status)
        assertTrue(outcome.degraded)
        assertEquals("SocketTimeoutException", outcome.detail)
    }

    @Test
    fun otherInterruptedIoIsATimeout() = runBlocking {
        assertEquals(SourceStatus.TIMEOUT, runSource { throw InterruptedIOException("cancelled") }.status)
    }

    @Test
    fun aSourceThatOutlivesItsDeadlineIsReportedAsAHang() = runBlocking {
        val outcome = runSource(deadlineMs = 100L) { delay(5_000); listOf(oneSong()) }
        assertEquals(SourceStatus.HANG, outcome.status)
        assertTrue(outcome.degraded)
    }

    @Test
    fun parseFailureIsReportedWithItsExceptionClass() = runBlocking {
        val outcome = runSource { throw JsonSyntaxException("bad json") }
        assertEquals(SourceStatus.PARSE_ERROR, outcome.status)
        assertEquals("JsonSyntaxException", outcome.detail)
        assertEquals(SourceStatus.PARSE_ERROR, runSource { throw IllegalStateException("not an object") }.status)
    }

    @Test
    fun httpErrorCodeAndUrlReachTheOutcome() = runBlocking {
        val outcome = runSource { reporter ->
            reporter.url = "search.kuwo.cn/r.s"
            throw HttpCodeException(403, null)
        }
        assertEquals(SourceStatus.HTTP_ERROR, outcome.status)
        assertEquals(403, outcome.httpCode)
        assertEquals("HTTP 403", outcome.detail)
        assertEquals("search.kuwo.cn/r.s", outcome.url)
    }

    @Test
    fun unknownFailuresAreErrorsNotEmpty() = runBlocking {
        val outcome = runSource { throw RuntimeException("boom") }
        assertEquals(SourceStatus.ERROR, outcome.status)
        assertEquals("RuntimeException", outcome.detail)
        assertTrue(outcome.degraded)
    }

    @Test
    fun cancellationIsNeverSwallowed() {
        assertThrows(CancellationException::class.java) {
            runBlocking { runSource { throw CancellationException("search replaced") } }
        }
    }

    @Test
    fun cancellingTheCallerCancelsTheSourceCall() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = launch { SourceCall.run(FakeSource({ started.complete(Unit); delay(30_000); emptyList() }), "晴天") }
        started.await()
        val cancelAt = System.nanoTime()
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue("The fan-out must follow the caller's cancellation", job.isCancelled)
        assertTrue("Cancellation must interrupt the source, not wait for its own timeout", System.nanoTime() - cancelAt < 2_000_000_000L)
    }

    @Test
    fun classifyMatchesTheRunner() {
        assertEquals(SourceStatus.TIMEOUT, SourceCall.classify(SocketTimeoutException("t")))
        assertEquals(SourceStatus.TIMEOUT, SourceCall.classify(InterruptedIOException("i")))
        assertEquals(SourceStatus.HTTP_ERROR, SourceCall.classify(HttpCodeException(500)))
        assertEquals(SourceStatus.PARSE_ERROR, SourceCall.classify(JsonSyntaxException("j")))
        assertEquals(SourceStatus.PARSE_ERROR, SourceCall.classify(IllegalStateException("s")))
        assertEquals(SourceStatus.ERROR, SourceCall.classify(null))
        assertEquals(SourceStatus.ERROR, SourceCall.classify(RuntimeException("r")))
    }
}
