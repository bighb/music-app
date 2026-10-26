package com.personal.musicapp

import android.app.Application
import com.personal.musicapp.data.local.LocalMusicRepository
import com.personal.musicapp.data.source.AudioSourceManager
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

    override fun onCreate() {
        super.onCreate()
        instance = this

        localRepository = LocalMusicRepository(this)
        playerManager = MusicPlayerManager(this)
        audioSourceManager = AudioSourceManager()
    }

    override fun onTerminate() {
        playerManager.release()
        super.onTerminate()
    }
}
