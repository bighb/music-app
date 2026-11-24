package com.personal.musicapp

import com.personal.musicapp.data.source.HttpPolicy
import com.personal.musicapp.data.source.KuwoAudioSource
import com.personal.musicapp.data.source.NetMusicAudioSource
import com.personal.musicapp.data.source.YouTubeAudioSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeoutPolicyTest {

    @After
    fun resetScale() {
        HttpPolicy.scalePercent = 100
    }

    @Test
    fun searchClientCallTimeoutIsTheSourcesOwnDeadline() {
        val realSources = listOf(KuwoAudioSource(), NetMusicAudioSource(), YouTubeAudioSource())
        for (source in realSources) {
            val client = HttpPolicy.searchClient(source.sourceName)
            assertEquals(
                "callTimeout of ${source.sourceName} must equal its search deadline",
                source.searchDeadlineMs,
                client.callTimeoutMillis.toLong()
            )
        }
    }

    @Test
    fun noStageOfASearchMayExceedItsOwnDeadline() {
        for (name in listOf(HttpPolicy.KUWO, HttpPolicy.NETEASE, HttpPolicy.YOUTUBE)) {
            val client = HttpPolicy.searchClient(name)
            val deadline = HttpPolicy.searchDeadlineMs(name)
            assertTrue("$name connect > deadline", client.connectTimeoutMillis <= deadline)
            assertTrue("$name read > deadline", client.readTimeoutMillis <= deadline)
            assertTrue("$name write > deadline", client.writeTimeoutMillis <= deadline)
        }
    }

    @Test
    fun youtubeBudgetIsNotShorterThanItsReadTimeout() {
        val client = HttpPolicy.searchClient(HttpPolicy.YOUTUBE)
        assertTrue("YouTube was discarded before its own read could finish", client.callTimeoutMillis.toLong() >= client.readTimeoutMillis)
        assertEquals(HttpPolicy.YOUTUBE_SEARCH_MS, client.callTimeoutMillis.toLong())
    }

    @Test
    fun everyClientSharesOnePoolAndDispatcher() {
        val clients = listOf(
            HttpPolicy.searchClient(HttpPolicy.KUWO),
            HttpPolicy.searchClient(HttpPolicy.NETEASE),
            HttpPolicy.searchClient(HttpPolicy.YOUTUBE),
            HttpPolicy.resolveClient(),
            HttpPolicy.playerClient(),
            HttpPolicy.coverClient()
        )
        for (client in clients) {
            assertSame(client.toString(), HttpPolicy.shared.connectionPool, client.connectionPool)
            assertSame(client.toString(), HttpPolicy.shared.dispatcher, client.dispatcher)
        }
    }

    @Test
    fun perRequestBudgetsAreBoundedAndOnlyRedirectProbesRefuseRedirects() {
        val resolve = HttpPolicy.resolveClient()
        assertEquals(HttpPolicy.CONVERT_MS, resolve.callTimeoutMillis.toLong())
        assertFalse(resolve.followRedirects)
        assertFalse(resolve.followSslRedirects)

        val player = HttpPolicy.playerClient()
        assertEquals(HttpPolicy.YOUTUBE_PLAYER_MS, player.callTimeoutMillis.toLong())
        assertTrue(player.followRedirects)

        val cover = HttpPolicy.coverClient()
        assertEquals(HttpPolicy.COVER_MS, cover.callTimeoutMillis.toLong())
        assertTrue(cover.followRedirects)

        assertEquals(HttpPolicy.YOUTUBE_BOOTSTRAP_MS, HttpPolicy.resolveClient(HttpPolicy.YOUTUBE_BOOTSTRAP_MS).callTimeoutMillis.toLong())
    }

    @Test
    fun scaleIsClampedAndRestoresProductionBudgets() {
        val production = HttpPolicy.searchDeadlineMs(HttpPolicy.KUWO)
        HttpPolicy.scalePercent = 45
        assertEquals(production * 45 / 100, HttpPolicy.searchDeadlineMs(HttpPolicy.KUWO))
        HttpPolicy.scalePercent = 1000
        assertEquals(300, HttpPolicy.scalePercent)
        HttpPolicy.scalePercent = 1
        assertEquals(25, HttpPolicy.scalePercent)
        HttpPolicy.scalePercent = 100
        assertEquals(HttpPolicy.KUWO_SEARCH_MS, HttpPolicy.searchDeadlineMs(HttpPolicy.KUWO))
        assertEquals(HttpPolicy.NETEASE_SEARCH_MS, HttpPolicy.searchDeadlineMs(HttpPolicy.NETEASE))
        assertEquals(HttpPolicy.YOUTUBE_SEARCH_MS, HttpPolicy.searchDeadlineMs(HttpPolicy.YOUTUBE))
    }

    @Test
    fun userAgentMatchesTheClientThatMintedTheUrl() {
        assertEquals(HttpPolicy.YOUTUBE_USER_AGENT, HttpPolicy.userAgentFor("https://rr3---sn-abc.googlevideo.com/videoplayback?id=1"))
        assertEquals(HttpPolicy.YOUTUBE_USER_AGENT, HttpPolicy.userAgentFor("https://www.youtube.com/youtubei/v1/player"))
        assertEquals(HttpPolicy.DESKTOP_UA, HttpPolicy.userAgentFor("http://antiserver.kuwo.cn/anti.s?rid=1"))
        assertEquals(HttpPolicy.DESKTOP_UA, HttpPolicy.userAgentFor("https://m704.music.126.net/a.mp3"))
        assertNotEquals(HttpPolicy.YOUTUBE_USER_AGENT, HttpPolicy.userAgentFor("https://m801.music.126.net/a.mp3"))
    }

    @Test
    fun redactDropsQueryAndSignature() {
        val redacted = HttpPolicy.redact("https://rr3---sn-abc.googlevideo.com/videoplayback?sig=SECRET&expire=1")
        assertFalse(redacted.contains("SECRET"))
        assertFalse(redacted.contains("?"))
        assertTrue(redacted.length <= 64)
    }
}
