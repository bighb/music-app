package com.personal.musicapp.data.source

import com.personal.musicapp.data.model.Song

/**
 * Incremental merge of a multi-source fan-out. Order of arrival is preserved for display;
 * duplicates (same id, or same title AND artist) keep one row, preferring domestic sources.
 * The dropped rows stay reachable through [alternativesFor] so the resolver can try them.
 */
class SearchMerger(private val maxTrackKeys: Int = 400) {

    private val lock = Any()
    private val seenIds = HashSet<String>()
    private val youtubeSongs = ArrayList<Song>()
    private val domesticSongs = ArrayList<Song>()
    private val keptByKey = HashMap<String, Song>()
    private val byTrackKey = object : LinkedHashMap<String, MutableList<Song>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<Song>>?): Boolean {
            return size > maxTrackKeys
        }
    }

    @Volatile
    var mergedDrops: Int = 0
        private set

    fun reset() {
        synchronized(lock) {
            seenIds.clear()
            youtubeSongs.clear()
            domesticSongs.clear()
            keptByKey.clear()
            byTrackKey.clear()
            mergedDrops = 0
        }
    }

    /** @return true when the visible snapshot changed. */
    fun add(songs: List<Song>): Boolean {
        var changed = false
        synchronized(lock) {
            for (song in songs) {
                if (!seenIds.add(song.id)) continue
                val key = trackKey(song)
                if (key == null) {
                    keep(song)
                    changed = true
                    continue
                }
                val bucket = byTrackKey.getOrPut(key) { ArrayList(2) }
                bucket.removeAll { it.id == song.id }
                bucket.add(song)

                val kept = keptByKey[key]
                when {
                    kept == null -> {
                        keep(song)
                        keptByKey[key] = song
                        changed = true
                    }
                    isPreferredOver(song, kept) -> {
                        drop(kept)
                        keep(song)
                        keptByKey[key] = song
                        changed = true
                    }
                    else -> mergedDrops++
                }
            }
        }
        return changed
    }

    fun snapshot(): List<Song> {
        synchronized(lock) {
            if (youtubeSongs.isEmpty()) return ArrayList(domesticSongs)
            if (domesticSongs.isEmpty()) return ArrayList(youtubeSongs)
            return ArrayList<Song>(youtubeSongs.size + domesticSongs.size).apply {
                addAll(youtubeSongs)
                addAll(domesticSongs)
            }
        }
    }

    /** Other-source rows of the same recording, excluding [song] itself. May be empty. */
    fun alternativesFor(song: Song): List<Song> {
        val key = trackKey(song) ?: return emptyList()
        synchronized(lock) {
            val bucket = byTrackKey[key] ?: return emptyList()
            return bucket.filter { it.id != song.id }
        }
    }

    fun trackKeyCount(): Int {
        synchronized(lock) {
            return byTrackKey.size
        }
    }

    private fun keep(song: Song) {
        if (isYouTube(song)) youtubeSongs.add(song) else domesticSongs.add(song)
    }

    private fun drop(song: Song) {
        if (isYouTube(song)) youtubeSongs.remove(song) else domesticSongs.remove(song)
        mergedDrops++
    }

    /** A row displaces an already-kept row only when its source ranks higher (酷我 → 网易 → YouTube). */
    private fun isPreferredOver(candidate: Song, kept: Song): Boolean = rank(candidate) < rank(kept)

    private fun rank(song: Song): Int = when {
        isYouTube(song) -> 2
        song.source.equals(HttpPolicy.NETEASE, ignoreCase = true) -> 1
        else -> 0
    }

    private fun isYouTube(song: Song): Boolean = song.source.equals(HttpPolicy.YOUTUBE, ignoreCase = true)

    companion object {
        /** Exact title + artist, case/whitespace insensitive. Null when either field is blank. */
        fun trackKey(song: Song): String? {
            val title = song.title.trim().lowercase()
            val artist = song.artist.trim().lowercase()
            if (title.isEmpty() || artist.isEmpty()) return null
            return "$title\u0000$artist"
        }
    }
}
