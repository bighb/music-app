package com.personal.musicapp

import com.personal.musicapp.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistAndHistoryTest {

    private fun createDummySong(id: String, title: String): Song {
        return Song(
            id = id,
            title = title,
            artist = "测试歌手",
            audioUrl = "http://example.com/$id.mp3"
        )
    }

    @Test
    fun testPlayNextQueueLogic() {
        val s1 = createDummySong("1", "Song 1")
        val s2 = createDummySong("2", "Song 2")
        val s3 = createDummySong("3", "Song 3")
        val s4 = createDummySong("4", "Song 4")

        // Initial playlist: s1 (playing), s2, s3
        val currentPlaylist = mutableListOf(s1, s2, s3)
        val currentPlaying = s1

        // Insert s4 as play next (should be right after currentPlaying at index 1)
        val currentIdx = currentPlaylist.indexOfFirst { it.id == currentPlaying.id }
        val insertPos = if (currentIdx != -1) currentIdx + 1 else currentPlaylist.size
        currentPlaylist.add(insertPos, s4)

        assertEquals(4, currentPlaylist.size)
        assertEquals(s4.id, currentPlaylist[1].id)
        assertEquals(s2.id, currentPlaylist[2].id)
    }

    @Test
    fun testAddToPlaylistLogic() {
        val s1 = createDummySong("1", "Song 1")
        val s2 = createDummySong("2", "Song 2")
        val s3 = createDummySong("3", "Song 3")

        val currentPlaylist = mutableListOf(s1, s2)
        if (!currentPlaylist.any { it.id == s3.id }) {
            currentPlaylist.add(s3)
        }

        assertEquals(3, currentPlaylist.size)
        assertEquals(s3.id, currentPlaylist.last().id)
    }

    @Test
    fun testSearchHistoryDeduplication() {
        val history = mutableListOf<String>()

        fun addQuery(q: String) {
            val trimmed = q.trim()
            history.removeAll { it.equals(trimmed, ignoreCase = true) }
            history.add(0, trimmed)
            if (history.size > 15) history.removeAt(history.lastIndex)
        }

        addQuery("周杰伦")
        addQuery("晴天")
        addQuery("周杰伦") // Re-adding 周杰伦 should bump it to front

        assertEquals(2, history.size)
        assertEquals("周杰伦", history[0])
        assertEquals("晴天", history[1])
    }

    @Test
    fun testPlayHistoryOrderAndLimit() {
        val history = mutableListOf<Song>()

        fun record(song: Song) {
            history.removeAll { it.id == song.id }
            history.add(0, song)
            if (history.size > 100) history.removeAt(history.lastIndex)
        }

        val s1 = createDummySong("1", "Song 1")
        val s2 = createDummySong("2", "Song 2")
        record(s1)
        record(s2)
        record(s1) // Playing s1 again should bump it to front

        assertEquals(2, history.size)
        assertEquals("1", history[0].id)
        assertEquals("2", history[1].id)
    }
}
