package com.personal.musicapp

import com.personal.musicapp.data.source.HttpReporter
import com.personal.musicapp.data.source.NetMusicAudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The search payload carries no cover: parsing must yield complete rows before any cover request. */
class NeteaseParseTest {

    private fun body(firstWithPic: Boolean): String {
        val album = if (firstWithPic) {
            """{"id":1,"name":"叶惠美","picId":109951163,"picUrl":"http://p1.music.126.net/a.jpg"}"""
        } else {
            """{"id":1,"name":"叶惠美","picId":109951163}"""
        }
        val second = """{"id":2,"name":"稻香","duration":223000,"artists":[{"id":9,"name":"周杰伦"}],"album":{"id":2,"name":"魔杰座","picId":1},"fee":1}"""
        return """{"result":{"songs":[{"id":186016,"name":"晴天","duration":269000,"artists":[{"id":6452,"name":"周杰伦"}],"album":$album},$second],"songCount":2},"code":200}"""
    }

    private val source = NetMusicAudioSource()

    @Test
    fun rowsAreCompleteWithoutAPicUrlInThePayload() {
        val songs = source.parseSearchBody(body(firstWithPic = false), HttpReporter())
        assertEquals(2, songs.size)
        val first = songs[0]
        assertEquals("net_186016", first.id)
        assertEquals("晴天", first.title)
        assertEquals("周杰伦", first.artist)
        assertEquals(269_000L, first.durationMs)
        assertEquals("https://music.163.com/song/media/outer/url?id=186016.mp3", first.audioUrl)
        assertEquals("", first.coverUrl)
    }

    @Test
    fun aPicUrlInThePayloadIsUsedDirectly() {
        val songs = source.parseSearchBody(body(firstWithPic = true), HttpReporter())
        assertEquals("https://p1.music.126.net/a.jpg?param=400y400", songs[0].coverUrl)
        assertEquals("", songs[1].coverUrl)
    }

    @Test
    fun aMissingResultIsAnEmptyAnswerThatNamesTheField() {
        val reporter = HttpReporter()
        assertTrue(source.parseSearchBody("""{"code":200}""", reporter).isEmpty())
        assertEquals("field=result", reporter.detail)
        assertTrue(source.parseSearchBody("""{"result":{"songCount":0}}""", reporter).isEmpty())
        assertEquals("field=result.songs", reporter.detail)
    }

    @Test
    fun aCoverFailureCanOnlyCostCovers() {
        val songs = source.parseSearchBody(body(firstWithPic = false), HttpReporter())
        assertSame("No covers fetched: the result list is returned untouched", songs, source.applyCovers(songs, emptyMap()))

        val withOneCover = source.applyCovers(songs, mapOf(186016L to "https://p3.music.126.net/b.jpg?param=400y400"))
        assertEquals(2, withOneCover.size)
        assertEquals("https://p3.music.126.net/b.jpg?param=400y400", withOneCover[0].coverUrl)
        assertSame(songs[1], withOneCover[1])
    }

    @Test
    fun outerUrlIsTheNeteasePlaceholderShape() {
        assertEquals("https://music.163.com/song/media/outer/url?id=186016.mp3", NetMusicAudioSource.outerUrl(186016L))
    }
}
