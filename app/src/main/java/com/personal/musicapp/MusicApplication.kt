package com.personal.musicapp

import android.app.Application
import com.personal.musicapp.data.local.LocalMusicRepository
import com.personal.musicapp.data.source.AudioSourceManager
import com.personal.musicapp.data.source.HttpPolicy
import com.personal.musicapp.data.source.KuwoAudioHelper
import com.personal.musicapp.data.source.LyricRepository
import com.personal.musicapp.data.source.OkHttpResolverHttp
import com.personal.musicapp.data.source.StreamResolver
import com.personal.musicapp.player.MusicPlayerManager

class MusicApplication : Application() {

    companion object {
        lateinit var instance: MusicApplication
            private set
    }

    lateinit var playerManager: MusicPlayerManager
        private set

    lateinit var localRepository: LocalMusicRepository
        private set

    lateinit var audioSourceManager: AudioSourceManager
        private set

    lateinit var lyricRepository: LyricRepository
        private set

    lateinit var streamResolver: StreamResolver
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        HttpPolicy.scalePercent = getSharedPreferences("music_app_prefs", MODE_PRIVATE)
            .all["search_budget_scale"]?.toString()?.toIntOrNull() ?: 100

        localRepository = LocalMusicRepository(this)
        audioSourceManager = AudioSourceManager(cookieProvider = { localRepository.youtubeCookie.value })
        lyricRepository = LyricRepository(this)

        streamResolver = StreamResolver(
            http = OkHttpResolverHttp(),
            resolveYouTube = { audioSourceManager.resolveYouTube(it) },
            findNeteaseByTitle = { title, artist -> audioSourceManager.findNeteaseByTitle(title, artist) },
            findKuwoUrlByTitle = { title, artist -> KuwoAudioHelper.getPlayableUrl(title, artist) },
            alternatives = { audioSourceManager.searchMerger.alternativesFor(it) }
        )

        playerManager = MusicPlayerManager(this)
    }

    override fun onTerminate() {
        playerManager.release()
        super.onTerminate()
    }
}
