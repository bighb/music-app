package com.personal.musicapp.service

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.personal.musicapp.MusicApplication
import com.personal.musicapp.util.AppLogger

class PlaybackService : MediaSessionService() {

    private val tag = "PlaybackService"
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(tag, "onCreate")
        val player = MusicApplication.instance.playerManager.exoPlayer
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        AppLogger.w(tag, "onDestroy (session released, player kept)")
        // The player is owned by MusicApplication and outlives this service; releasing it here
        // leaves MusicPlayerManager driving a dead player (stuck in buffering forever).
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
