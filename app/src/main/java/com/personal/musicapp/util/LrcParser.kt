package com.personal.musicapp.util

import com.personal.musicapp.data.model.LyricEntry

object LrcParser {
    private val tagRegex = Regex("""\[(\d{1,2}):(\d{1,2})(?:[\.:](\d{1,3}))?]""")

    fun parse(lrcContent: String): List<LyricEntry> {
        if (lrcContent.isBlank()) return emptyList()

        val entries = mutableListOf<LyricEntry>()
        val lines = lrcContent.lines()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // Find all timestamp matches on this line
            val matches = tagRegex.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            // The lyric text is whatever is after the last timestamp
            val lastMatch = matches.last()
            val text = trimmed.substring(lastMatch.range.last + 1).trim()
            if (text.isEmpty()) continue

            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                val msRaw = m.groupValues.getOrNull(3) ?: "0"
                val ms = when (msRaw.length) {
                    1 -> msRaw.toLong() * 100
                    2 -> msRaw.toLong() * 10
                    else -> msRaw.take(3).toLong()
                }
                val totalMs = min * 60 * 1000 + sec * 1000 + ms
                entries.add(LyricEntry(totalMs, text))
            }
        }

        return entries.sortedBy { it.timeMs }
    }

    fun findCurrentIndex(lyrics: List<LyricEntry>, positionMs: Long): Int {
        if (lyrics.isEmpty()) return -1
        if (positionMs < lyrics[0].timeMs) return 0

        var low = 0
        var high = lyrics.size - 1
        var bestIndex = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lyrics[mid].timeMs <= positionMs) {
                bestIndex = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return bestIndex
    }
}
