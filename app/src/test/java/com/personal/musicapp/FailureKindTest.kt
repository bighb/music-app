package com.personal.musicapp

import com.personal.musicapp.data.source.HttpPolicy
import com.personal.musicapp.data.source.ResolveAttempt
import com.personal.musicapp.data.source.ResolveResult
import com.personal.musicapp.data.source.SourceDiagnostics
import com.personal.musicapp.data.source.SourceOutcome
import com.personal.musicapp.data.source.SourceStatus
import com.personal.musicapp.data.source.searchSettled
import com.personal.musicapp.data.source.FailureKind
import com.personal.musicapp.player.PlaybackFailure
import com.personal.musicapp.player.failureMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureKindTest {

    private fun ledger(vararg outcomes: String): List<ResolveAttempt> =
        outcomes.mapIndexed { i, o -> ResolveAttempt("kw_1", if (i % 2 == 0) HttpPolicy.KUWO else HttpPolicy.NETEASE, "c$i", httpCode = 403, outcome = o) }

    @Test
    fun onlyASourceOutageIsRetryable() {
        assertTrue(PlaybackFailure(FailureKind.SOURCE_UNAVAILABLE, "酷我 超时").retryable)
        assertFalse(PlaybackFailure(FailureKind.NO_FREE_SOURCE, "").retryable)
        assertFalse(PlaybackFailure(FailureKind.PROMO_REJECTED, "").retryable)
    }

    @Test
    fun anOutageNeverClaimsCopyrightOrAMembershipOnlyRelease() {
        val message = failureMessage("晴天", FailureKind.SOURCE_UNAVAILABLE, "酷我 超时 · 网易 HTTP 403")
        assertTrue(message, message.contains("晴天"))
        assertTrue(message, message.contains("暂时连不上"))
        assertTrue(message, message.contains("酷我 超时"))
        assertFalse(message, message.contains("版权"))
        assertFalse(message, message.contains("会员"))

        val bare = failureMessage("晴天", FailureKind.SOURCE_UNAVAILABLE, "")
        assertEquals("《晴天》音源暂时连不上，可稍后重试", bare)
    }

    @Test
    fun noFreeSourceNamesTheLikelyReason() {
        val message = failureMessage("晴天", FailureKind.NO_FREE_SOURCE, "")
        assertEquals("《晴天》在可用免费音源里没有完整音频（可能只有会员版）", message)
        assertFalse(message, message.contains("版权保护"))
    }

    @Test
    fun promoClipSaysItWasAShortClip() {
        assertEquals("《晴天》只找到试听/推广片段，已跳过", failureMessage("晴天", FailureKind.PROMO_REJECTED, ""))
    }

    @Test
    fun theLedgerIsReadableAndNeverCarriesUrls() {
        val attempts = listOf(
            ResolveAttempt("kw_1", HttpPolicy.KUWO, "kuwo:1", httpCode = 403, elapsedMs = 412, outcome = "http_error"),
            ResolveAttempt("kw_1", HttpPolicy.NETEASE, "netease:2", elapsedMs = 4000, outcome = "timeout"),
            ResolveAttempt("kw_1", HttpPolicy.YOUTUBE, "youtube:x", elapsedMs = 10, outcome = "blocked"),
            ResolveAttempt("kw_1", HttpPolicy.KUWO, "kuwo-title", elapsedMs = 10, outcome = "ok")
        )
        val description = ResolveResult(null, FailureKind.SOURCE_UNAVAILABLE, attempts).describeFailures()
        assertEquals("酷我 HTTP 403 · 网易云 超时", description)
        assertEquals("酷我 试听片段 · 网易云 试听片段", ResolveResult(null, FailureKind.PROMO_REJECTED, ledger("promo", "promo")).describeFailures())
        assertEquals("", ResolveResult(null, FailureKind.NONE, emptyList()).describeFailures())
    }

    @Test
    fun sourceLinesShareOneGreppableFormat() {
        val timeout = SourceOutcome(HttpPolicy.NETEASE, SourceStatus.TIMEOUT, httpCode = 0, elapsedMs = 6003, url = "music.163.com/api/search/get/web", detail = "SocketTimeoutException")
        val line = SourceDiagnostics.sourceLine(timeout, "晴天")
        assertTrue(line, line.startsWith("[SRC]  net  "))
        assertTrue(line, line.contains("q=\"晴天\""))
        assertTrue(line, line.contains("http=- "))
        assertTrue(line, line.contains("6003ms"))
        assertTrue(line, line.contains("n=0 "))
        assertTrue(line, line.contains("status=timeout"))
        assertTrue(line, line.contains("url=music.163.com/api/search/get/web"))

        val ok = SourceDiagnostics.sourceLine(SourceOutcome(HttpPolicy.KUWO, SourceStatus.OK, count = 20, httpCode = 200), "晴天")
        assertTrue(ok, ok.contains("http=200 "))
        assertTrue(ok, ok.contains("n=20 "))
        assertTrue(ok, ok.contains("status=ok"))
    }

    @Test
    fun statusTokensMatchTheLogFormat() {
        assertEquals("playability-blocked", SourceDiagnostics.statusToken(SourceStatus.PLAYABILITY_BLOCKED))
        assertEquals("http-error", SourceDiagnostics.statusToken(SourceStatus.HTTP_ERROR))
        assertEquals("parse-error", SourceDiagnostics.statusToken(SourceStatus.PARSE_ERROR))
        assertEquals("kw", SourceDiagnostics.shortName(HttpPolicy.KUWO))
        assertEquals("net", SourceDiagnostics.shortName(HttpPolicy.NETEASE))
        assertEquals("yt", SourceDiagnostics.shortName(HttpPolicy.YOUTUBE))
    }

    @Test
    fun onlyAnAnsweredSourceHasNoProblemToken() {
        assertNull(SourceDiagnostics.problemToken(SourceOutcome("x", SourceStatus.OK)))
        assertNull(SourceDiagnostics.problemToken(SourceOutcome("x", SourceStatus.EMPTY)))
        assertEquals("timeout", SourceDiagnostics.problemToken(SourceOutcome("x", SourceStatus.HANG)))
        assertEquals("http=403", SourceDiagnostics.problemToken(SourceOutcome("x", SourceStatus.HTTP_ERROR, httpCode = 403)))
        assertEquals("parse-error", SourceDiagnostics.problemToken(SourceOutcome("x", SourceStatus.PARSE_ERROR)))
    }

    @Test
    fun settledMeansBothDomesticSourcesAnswered() {
        val kuwo = SourceOutcome(HttpPolicy.KUWO, SourceStatus.OK)
        val netease = SourceOutcome(HttpPolicy.NETEASE, SourceStatus.TIMEOUT)
        val youtube = SourceOutcome(HttpPolicy.YOUTUBE, SourceStatus.OK)

        assertFalse(emptyList<SourceOutcome>().searchSettled())
        assertFalse(listOf(kuwo, youtube).searchSettled())
        assertFalse(listOf(youtube).searchSettled())
        assertTrue("A timed-out source has still answered", listOf(kuwo, netease).searchSettled())
        assertTrue(listOf(youtube, netease, kuwo).searchSettled())
    }

    @Test
    fun degradedMeansAnythingOtherThanAnswered() {
        assertFalse(SourceOutcome("x", SourceStatus.OK).degraded)
        assertFalse(SourceOutcome("x", SourceStatus.EMPTY).degraded)
        for (status in listOf(SourceStatus.TIMEOUT, SourceStatus.HANG, SourceStatus.HTTP_ERROR, SourceStatus.PARSE_ERROR, SourceStatus.ERROR, SourceStatus.PLAYABILITY_BLOCKED)) {
            assertTrue(status.name, SourceOutcome("x", status).degraded)
        }
    }
}
