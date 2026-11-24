package com.personal.musicapp.data.source

import com.personal.musicapp.data.model.SearchSuggestion
import com.personal.musicapp.data.model.Song
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import java.util.Collections

class AudioSourceManager(
    private val cookieProvider: () -> String? = { null }
) {

    private val netMusic = NetMusicAudioSource()
    private val kuwoMusic = KuwoAudioSource()
    private val youtube = YouTubeAudioSource(cookieProvider)
    private val douyin = DouyinAudioSource(fallbackMusicSource = netMusic)

    private val suggestHelper = SearchSuggestHelper()

    private val linkSources: List<AudioSource> = listOf(douyin, youtube)
    internal var searchSources: List<AudioSource> = listOf(kuwoMusic, netMusic, youtube)

    val searchCache = SearchCache()
    val searchMerger = SearchMerger()

    fun findSourceForLink(text: String): AudioSource? {
        return linkSources.firstOrNull { it.canHandleLink(text) }
    }

    suspend fun parseShareLink(text: String): Song? {
        val source = findSourceForLink(text) ?: douyin
        return source.parseShareLink(text)
    }

    suspend fun resolveYouTubeStream(videoId: String): String? = youtube.resolveStreamUrl(videoId)

    suspend fun resolveYouTube(videoId: String): YtResolveResult = youtube.resolveStream(videoId)

    /** Netease lookup by title, for the resolver's cross-source fallback. */
    suspend fun findNeteaseByTitle(title: String, artist: String): Song? = netMusic.findByTitle(title, artist)

    suspend fun getSuggestions(query: String, localHistory: List<String> = emptyList()): List<SearchSuggestion> {
        return suggestHelper.getSuggestions(query, localHistory)
    }

    /**
     * Fans out to every source concurrently. Each source owns its deadline (its OkHttp
     * callTimeout); `settled` flips once both domestic sources have answered, so YouTube's
     * longer budget never delays the "nothing found" verdict.
     */
    fun searchStream(query: String): Flow<SearchBatch> = channelFlow {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            send(SearchBatch(emptyList(), emptyList(), settled = true))
            return@channelFlow
        }

        val started = System.nanoTime()
        val hit = searchCache.getHit(trimmed)
        if (hit != null) {
            send(SearchBatch(hit.songs, emptyList(), settled = true, cached = true))
            if (hit.complete) return@channelFlow
        }

        val merger = searchMerger
        merger.reset()
        if (hit != null) merger.add(hit.songs)

        val outcomes = Collections.synchronizedList(ArrayList<SourceOutcome>())

        var emittedCount = merger.snapshot().size
        var emittedSettled = false

        coroutineScope {
            for (source in searchSources) {
                launch {
                    val outcome = SourceCall.run(source, trimmed)
                    SourceDiagnostics.logSource(outcome, trimmed)
                    outcomes.add(outcome)
                    if (outcome.status == SourceStatus.OK) merger.add(outcome.songs)

                    val snapshot = merger.snapshot()
                    val nowSettled = outcomes.searchSettled()
                    val allDone = outcomes.size == searchSources.size
                    if (allDone || snapshot.size != emittedCount || nowSettled != emittedSettled) {
                        emittedCount = snapshot.size
                        emittedSettled = nowSettled
                        trySend(SearchBatch(snapshot, outcomes.toList(), nowSettled))
                    }
                }
            }
        }

        val all = outcomes.toList()
        val finalSongs = merger.snapshot()
        if (finalSongs.isNotEmpty()) {
            searchCache.put(trimmed, finalSongs, complete = all.none { it.degraded })
        }
        SourceDiagnostics.logSearch(
            query = trimmed,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            outcomes = all,
            cached = hit != null,
            merged = merger.mergedDrops,
            songs = finalSongs.size,
            settled = all.searchSettled()
        )
    }

    suspend fun search(query: String): List<Song> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val hit = searchCache.getHit(trimmed)
        if (hit != null && hit.complete) return hit.songs
        return searchStream(trimmed).lastOrNull()?.songs ?: emptyList()
    }
}
