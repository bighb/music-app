package com.personal.musicapp

import com.personal.musicapp.data.model.Song
import com.personal.musicapp.data.source.SearchSuggestHelper
import com.personal.musicapp.util.SearchInputUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSuggestionAndRankingTest {

    @Test
    fun testShareLinkDetectionAndExtraction() {
        // Plain query
        assertFalse(SearchInputUtils.isShareContentOrUrl("周杰伦 晴天"))
        assertFalse(SearchInputUtils.isShareContentOrUrl("Taylor Swift"))

        // Direct URLs
        assertTrue(SearchInputUtils.isShareContentOrUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(SearchInputUtils.isShareContentOrUrl("http://v.douyin.com/xyz123/"))

        // Social share snippets with mixed text
        val douyinSnippet = "7.11 复制打开抖音，看看【陈小春的作品】在全世界的华人圈中 https://v.douyin.com/iR3Ncvb/ 01/01 a@b.com"
        assertTrue(SearchInputUtils.isShareContentOrUrl(douyinSnippet))
        val extractedUrl = SearchInputUtils.extractUrl(douyinSnippet)
        assertNotNull(extractedUrl)
        assertEquals("https://v.douyin.com/iR3Ncvb/", extractedUrl)
    }

    @Test
    fun testSearchSuggestHelperSuppressesShareLinks() = runBlocking {
        val helper = SearchSuggestHelper()
        val history = listOf("晴天", "七里香", "稻香")

        // Share snippet should immediately yield empty suggestions
        val shareSnippet = "看看这个好听的歌曲 https://v.douyin.com/iR3Ncvb/"
        val suggestions = helper.getSuggestions(shareSnippet, history)
        assertTrue("Share content must not trigger search suggestions", suggestions.isEmpty())

        // Ordinary search keyword should match history and fetch suggestions
        val keywordSuggestions = helper.getSuggestions("晴", history)
        assertTrue("Normal keyword should return suggestions", keywordSuggestions.isNotEmpty())
        assertTrue("Top item should be history match", keywordSuggestions.any { it.isHistory && it.keyword == "晴天" })
        assertTrue("History item should have '历史' source", keywordSuggestions.any { it.isHistory && it.source == "历史" })
        assertTrue("Online item should have 'QQ' source", keywordSuggestions.any { !it.isHistory && it.source == "QQ" })
    }

    @Test
    fun testYouTubePriorityRankingLogic() {
        val kuwoSong = Song(id = "kw_1", title = "晴天", artist = "周杰伦", audioUrl = "http://kuwo/1.mp3", source = "kuwo")
        val neteaseSong = Song(id = "163_1", title = "晴天 (Live)", artist = "周杰伦", audioUrl = "http://163/1.mp3", source = "netease")
        val ytSong = Song(id = "yt_1", title = "Jay Chou - Sunny Day", artist = "周杰伦", audioUrl = "http://yt/1.mp3", source = "YouTube")

        // Simulate stream accumulation logic:
        val ytSongs = mutableListOf<Song>()
        val otherSongs = mutableListOf<Song>()
        val seenIds = mutableSetOf<String>()

        fun addSongs(batch: List<Song>) {
            for (song in batch) {
                if (seenIds.add(song.id)) {
                    if (song.source.equals("YouTube", ignoreCase = true)) {
                        ytSongs.add(song)
                    } else {
                        otherSongs.add(song)
                    }
                }
            }
        }

        // 1. Domestic sources arrive first
        addSongs(listOf(kuwoSong, neteaseSong))
        val firstEmission = ytSongs + otherSongs
        assertEquals(2, firstEmission.size)
        assertEquals("kw_1", firstEmission[0].id)

        // 2. YouTube overseas source arrives asynchronously later
        addSongs(listOf(ytSong))
        val secondEmission = ytSongs + otherSongs
        assertEquals(3, secondEmission.size)
        // Verify YouTube is positioned at the top of online results
        assertEquals("YouTube source must be ranked at the front", "YouTube", secondEmission[0].source)
        assertEquals("yt_1", secondEmission[0].id)
        assertEquals("kw_1", secondEmission[1].id)
    }
}
