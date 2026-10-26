package com.personal.musicapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.personal.musicapp.service.PlaybackService
import com.personal.musicapp.ui.MainScreen
import com.personal.musicapp.ui.theme.MusicAppTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Permission result handled
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission for Android 13+ media controls
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Start playback service
        val serviceIntent = Intent(this, PlaybackService::class.java)
        startService(serviceIntent)

        // Extract shared text from incoming intent (if shared from Douyin app)
        val sharedText = extractSharedText(intent)

        setContent {
            MusicAppTheme {
                MainScreen(initialSharedText = sharedText)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sharedText = extractSharedText(intent)
        if (!sharedText.isNullOrEmpty()) {
            setContent {
                MusicAppTheme {
                    MainScreen(initialSharedText = sharedText)
                }
            }
        }
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        if (Intent.ACTION_SEND == intent.action && "text/plain" == intent.type) {
            return intent.getStringExtra(Intent.EXTRA_TEXT)
        }
        return null
    }
}
