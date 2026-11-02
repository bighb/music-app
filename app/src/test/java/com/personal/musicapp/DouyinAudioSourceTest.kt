package com.personal.musicapp

import com.personal.musicapp.data.source.DouyinAudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class DouyinAudioSourceTest {

    private val source = DouyinAudioSource()

    @Test
    fun testCanHandleLink() {
        assertTrue(source.canHandleLink("https://v.douyin.com/iabc123/"))
        assertTrue(source.canHandleLink("https://www.iesdouyin.com/share/video/712345/"))
        assertTrue(source.canHandleLink("https://www.douyin.com/music/712345/"))
        assertTrue(source.canHandleLink("复制打开抖音... https://v.douyin.com/xyz/"))
        assertFalse(source.canHandleLink("https://www.bilibili.com/video/BV1xx"))
        assertFalse(source.canHandleLink("https://youtu.be/abc"))
    }

    @Test
    fun testExtractTitleFromShareText() {
        val t1 = "7.91 10/24 N@j.sS 07/04 复制打开抖音，看看【周杰伦创作的原声《晴天》】 https://v.douyin.com/iabc123/"
        assertEquals("晴天", source.extractTitleFromShareText(t1))

        val t2 = "7.91 复制打开抖音，看看【小明发布的视频】 https://v.douyin.com/iabc123/"
        assertEquals("小明", source.extractTitleFromShareText(t2))

        val t3 = "复制打开抖音，看看【摄影师大刚的图文作品】 https://v.douyin.com/iabc123/"
        assertEquals("摄影师大刚", source.extractTitleFromShareText(t3))

        val t4 = "看看【《七里香》】 https://v.douyin.com/iabc123/"
        assertEquals("七里香", source.extractTitleFromShareText(t4))

        val t5 = "https://v.douyin.com/iabc123/"
        assertNull(source.extractTitleFromShareText(t5))
    }

    @Test
    fun testUrlPatternMatchesDifferentDouyinTypes() {
        val idTypePattern = Pattern.compile("/(?:share/)?(video|note|slides|music)/(\\d+)")
        val modalPattern = Pattern.compile("modal_id=(\\d+)")

        fun parseTypeAndId(url: String): Pair<String, String>? {
            val m = idTypePattern.matcher(url)
            if (m.find()) {
                val t = m.group(1) ?: "video"
                val type = if (t == "slides") "note" else t
                return Pair(type, m.group(2)!!)
            }
            val m2 = modalPattern.matcher(url)
            if (m2.find()) {
                return Pair("video", m2.group(1)!!)
            }
            return null
        }

        assertEquals(Pair("video", "71234567890"), parseTypeAndId("https://www.iesdouyin.com/share/video/71234567890/?region=CN"))
        assertEquals(Pair("note", "72222222222"), parseTypeAndId("https://www.iesdouyin.com/share/note/72222222222/?mid=1"))
        assertEquals(Pair("note", "73333333333"), parseTypeAndId("https://www.iesdouyin.com/share/slides/73333333333/"))
        assertEquals(Pair("music", "74444444444"), parseTypeAndId("https://www.iesdouyin.com/share/music/74444444444/?something=1"))
        assertEquals(Pair("video", "71234567890"), parseTypeAndId("https://www.douyin.com/video/71234567890"))
        assertEquals(Pair("note", "72222222222"), parseTypeAndId("https://www.douyin.com/note/72222222222"))
        assertEquals(Pair("music", "74444444444"), parseTypeAndId("https://www.douyin.com/music/74444444444"))
        assertEquals(Pair("video", "75555555555"), parseTypeAndId("https://www.douyin.com/discover?modal_id=75555555555"))
    }
}
