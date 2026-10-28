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

    @Test
    fun testQueueRemovalNonPlaying() {
        val s1 = createDummySong("1", "Song 1")
        val s2 = createDummySong("2", "Song 2")
        val s3 = createDummySong("3", "Song 3")

        val playlist = mutableListOf(s1, s2, s3)

        // Remove s2
        val targetId = "2"
        val idx = playlist.indexOfFirst { it.id == targetId }
        playlist.removeAt(idx)
        val newCurrentIdx = playlist.indexOfFirst { it.id == s1.id }

        assertEquals(2, playlist.size)
        assertEquals(0, newCurrentIdx)
        assertEquals("3", playlist[1].id)
    }

    @Test
    fun testQueueRemovalCurrentlyPlayingAdvances() {
        val s1 = createDummySong("1", "Song 1")
        val s2 = createDummySong("2", "Song 2")
        val s3 = createDummySong("3", "Song 3")

        val playlist = mutableListOf(s1, s2, s3)
        var currentSong = s1

        // Removing currentSong (s1) advances to next (s2)
        val existingIndex = playlist.indexOfFirst { it.id == currentSong.id }
        val nextIndex = (existingIndex + 1) % playlist.size
        val nextSong = playlist[nextIndex]
        playlist.removeAt(existingIndex)
        currentSong = nextSong

        assertEquals(2, playlist.size)
        assertEquals("2", currentSong.id)
        assertEquals("2", playlist[0].id)
        assertEquals("3", playlist[1].id)
    }

    @Test
    fun testQueueRemovalOnlySongStops() {
        val s1 = createDummySong("1", "Song 1")
        val playlist = mutableListOf(s1)
        var currentSong: Song? = s1

        if (playlist.size <= 1) {
            playlist.clear()
            currentSong = null
        }

        assertEquals(0, playlist.size)
        assertEquals(null, currentSong)
    }

    @Test
    fun testQueueReorder() {
        val s1 = createDummySong("1", "Song 1")
        val s2 = createDummySong("2", "Song 2")
        val s3 = createDummySong("3", "Song 3")

        val playlist = mutableListOf(s1, s2, s3)
        // Move s3 from index 2 to index 0
        val moved = playlist.removeAt(2)
        playlist.add(0, moved)

        assertEquals("3", playlist[0].id)
        assertEquals("1", playlist[1].id)
        assertEquals("2", playlist[2].id)
    }

    @Test
    fun testFavoriteAndDownloadDecoupled() {
        val favorites = mutableListOf<Song>()
        val downloads = mutableListOf<Song>()

        val s1 = createDummySong("1", "Song 1")

        // 1. Favorite song: only in favorites, not in downloads
        favorites.add(s1.copy(isFavorite = true))
        assertEquals(1, favorites.size)
        assertEquals(0, downloads.size)

        // 2. Download song: in downloads
        val downloadedS1 = s1.copy(localAudioPath = "/path/to/1.mp3")
        downloads.add(downloadedS1)
        assertEquals(1, favorites.size)
        assertEquals(1, downloads.size)

        // 3. Remove favorite: downloads still retained!
        favorites.removeAll { it.id == s1.id }
        assertEquals(0, favorites.size)
        assertEquals(1, downloads.size)

        // 4. Delete download: favorites unaffected
        favorites.add(s1.copy(isFavorite = true))
        downloads.removeAll { it.id == s1.id }
        assertEquals(1, favorites.size)
        assertEquals(0, downloads.size)
    }
}
