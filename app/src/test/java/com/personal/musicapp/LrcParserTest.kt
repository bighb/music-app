package com.personal.musicapp

import com.personal.musicapp.util.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun testStandardLrcParsing() {
        val lrc = """
            [ti:晴天]
            [ar:周杰伦]
            [al:叶惠美]
            [00:00.00]作词 : 周杰伦
            [00:02.50]作曲 : 周杰伦
            [00:29.00]故事的小黄花
            [00:32.450]从出生那年就飘着
            [00:36.12]童年的荡秋千
        """.trimIndent()

        val entries = LrcParser.parse(lrc)
        assertEquals(5, entries.size)

        assertEquals(0L, entries[0].timeMs)
        assertEquals("作词 : 周杰伦", entries[0].text)

        assertEquals(2500L, entries[1].timeMs)
        assertEquals("作曲 : 周杰伦", entries[1].text)

        assertEquals(29000L, entries[2].timeMs)
        assertEquals("故事的小黄花", entries[2].text)

        assertEquals(32450L, entries[3].timeMs)
        assertEquals("从出生那年就飘着", entries[3].text)

        assertEquals(36120L, entries[4].timeMs)
        assertEquals("童年的荡秋千", entries[4].text)
    }

    @Test
    fun testMultiTagSingleLineParsing() {
        val lrc = """
            [01:00.00][02:00.00]重复的高潮部分歌词
        """.trimIndent()

        val entries = LrcParser.parse(lrc)
        assertEquals(2, entries.size)
        assertEquals(60000L, entries[0].timeMs)
        assertEquals("重复的高潮部分歌词", entries[0].text)
        assertEquals(120000L, entries[1].timeMs)
        assertEquals("重复的高潮部分歌词", entries[1].text)
    }

    @Test
    fun testFindCurrentIndex() {
        val lrc = """
            [00:10.00]第一句
            [00:20.00]第二句
            [00:30.00]第三句
        """.trimIndent()

        val entries = LrcParser.parse(lrc)

        // Before first lyric
        assertEquals(0, LrcParser.findCurrentIndex(entries, 5000L))

        // Exactly at first lyric
        assertEquals(0, LrcParser.findCurrentIndex(entries, 10000L))

        // Between first and second lyric
        assertEquals(0, LrcParser.findCurrentIndex(entries, 15000L))

        // At second lyric
        assertEquals(1, LrcParser.findCurrentIndex(entries, 20000L))

        // Beyond last lyric
        assertEquals(2, LrcParser.findCurrentIndex(entries, 45000L))

        // Empty list handling
        assertEquals(-1, LrcParser.findCurrentIndex(emptyList(), 1000L))
    }

    @Test
    fun testEmptyOrInvalidLrc() {
        assertTrue(LrcParser.parse("").isEmpty())
        assertTrue(LrcParser.parse("   ").isEmpty())
        assertTrue(LrcParser.parse("[ti:No Time Tags Here]").isEmpty())
    }
}
