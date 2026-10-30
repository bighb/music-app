package com.personal.musicapp

import com.personal.musicapp.data.source.AudioSourceManager
import com.personal.musicapp.data.source.YouTubeAudioSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAudioSourceTest {

    @Test
    fun testCanHandleLink() {
        val yt = YouTubeAudioSource()
        assertTrue(yt.canHandleLink("https://www.youtube.com/watch?v=DYptgVvkVLQ"))
        assertTrue(yt.canHandleLink("https://youtu.be/DYptgVvkVLQ"))
        assertTrue(yt.canHandleLink("https://music.youtube.com/watch?v=DYptgVvkVLQ"))
        assertFalse(yt.canHandleLink("https://v.douyin.com/abc/"))
    }

    @Test
    fun testParseShareLink() = runBlocking {
        val yt = YouTubeAudioSource()
        val song = yt.parseShareLink("https://www.youtube.com/watch?feature=shared&v=DYptgVvkVLQ")
        assertNotNull("Song should be parsed from link", song)
        assertTrue("Song id should start with yt_", song!!.id.startsWith("yt_"))
        assertTrue("Song title should not be blank", song.title.isNotBlank())
    }

    @Test
    fun testYouTubeSearch() = runBlocking {
        val yt = YouTubeAudioSource()
        val songs = yt.search("周杰伦 晴天")

        println("=== YouTube Search Results ===")
        println("Returned count: ${songs.size}")
        assertTrue("YouTube search should return items", songs.isNotEmpty())

        val first = songs[0]
        println("First song: [${first.source}] ${first.title} - ${first.artist} (${first.audioUrl})")
        assertTrue("First song title should not be blank", first.title.isNotBlank())
        assertTrue("AudioUrl must start with youtube://", first.audioUrl.startsWith("youtube://"))
        assertTrue("CoverUrl must be valid", first.coverUrl.startsWith("http"))
    }

    @Test
    fun testYouTubeStreamResolution() = runBlocking {
        val yt = YouTubeAudioSource()
        val streamUrl = yt.resolveStreamUrl("DYptgVvkVLQ")

        println("Resolved stream URL: $streamUrl")
        assertNotNull("Stream URL must be resolved", streamUrl)
        assertTrue("Stream URL must be http/https", streamUrl!!.startsWith("http"))

        // Verify direct streaming playback with Range request
        val client = OkHttpClient()
        val req = Request.Builder()
            .url(streamUrl)
            .header("Range", "bytes=0-100")
            .build()

        client.newCall(req).execute().use { resp ->
            println("Stream Range Response code: ${resp.code}")
            assertTrue("Stream request should return 200 or 206", resp.code in 200..206)
        }
    }

    @Test
    fun testAggregatedSearchContainsYouTube() = runBlocking {
        val manager = AudioSourceManager()
        val results = manager.search("周杰伦")

        println("Total aggregated songs: ${results.size}")
        assertTrue("Aggregated search should return songs", results.isNotEmpty())

        val sources = results.map { it.source }.toSet()
        println("Aggregated sources: $sources")
        assertTrue("Should contain YouTube source", sources.contains("YouTube"))
    }
}
