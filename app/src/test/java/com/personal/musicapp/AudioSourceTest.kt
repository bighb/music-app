package com.personal.musicapp

import com.personal.musicapp.data.source.KuwoAudioHelper
import com.personal.musicapp.data.source.NetMusicAudioSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSourceTest {

    @Test
    fun testKuwoFallbackForRestrictedSong() = runBlocking {
        // Song that is 404 on Netease
        val url = KuwoAudioHelper.getPlayableUrl("玉芬啊玉芬你让彪哥好伤心")
        println("Kuwo Playable URL: $url")
        assertNotNull("Kuwo fallback should resolve playable stream", url)
        assertTrue("Stream should be http/https", url!!.startsWith("http"))

        // Verify that the stream returns HTTP 200 with audio content type
        val client = OkHttpClient()
        val req = Request.Builder().url(url).head().build()
        client.newCall(req).execute().use { resp ->
            println("Stream HTTP Status: ${resp.code}, Content-Type: ${resp.header("Content-Type")}")
            assertTrue("Stream must return 200 OK", resp.isSuccessful)
            val contentType = resp.header("Content-Type") ?: ""
            assertTrue("Content-Type must be audio", contentType.contains("audio") || contentType.contains("mpeg") || contentType.contains("octet-stream"))
        }
    }

    @Test
    fun testNetMusicSearchAndCovers() = runBlocking {
        val source = NetMusicAudioSource()
        val results = source.search("玉芬")

        println("=== Search Results for '玉芬' ===")
        println("Total songs returned: ${results.size}")
        assertTrue("Search should return results", results.isNotEmpty())

        val firstSong = results[0]
        println("First song title: ${firstSong.title}")
        println("First song artist: ${firstSong.artist}")
        println("First song coverUrl: ${firstSong.coverUrl}")
        assertTrue("Cover URL should not be empty", firstSong.coverUrl.isNotEmpty())
    }
}
