package com.personal.musicapp.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.personal.musicapp.MusicApplication
import com.personal.musicapp.data.model.Song
import com.personal.musicapp.ui.components.FullPlayerDialog
import com.personal.musicapp.ui.components.MiniPlayerBar
import com.personal.musicapp.ui.screens.DiscoverScreen
import com.personal.musicapp.ui.screens.FavoritesScreen
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    initialSharedText: String? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val app = MusicApplication.instance
    val playerManager = app.playerManager
    val localRepo = app.localRepository
    val sourceManager = app.audioSourceManager

    val playerState by playerManager.playerState.collectAsState()
    val favoritesList by localRepo.favorites.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var clipboardDetectedLink by remember { mutableStateOf<String?>(null) }

    fun checkClipboardForLink() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                    if (sourceManager.findSourceForLink(text) != null) {
                        clipboardDetectedLink = text
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun parseAndPlay(linkOrText: String) {
        isLoading = true
        errorMessage = null
        clipboardDetectedLink = null
        coroutineScope.launch {
            try {
                val song = sourceManager.parseShareLink(linkOrText)
                isLoading = false
                if (song != null) {
                    searchResults = listOf(song)
                    playerManager.playSong(song, listOf(song))
                    Toast.makeText(context, "解析成功: ${song.title}", Toast.LENGTH_SHORT).show()
                } else {
                    errorMessage = "未能解析到有效音频，请检查链接或网络后重试"
                }
            } catch (e: Exception) {
                isLoading = false
                errorMessage = "解析发生错误: ${e.localizedMessage ?: "未知网络错误"}"
            }
        }
    }

    fun performSearch() {
        val query = searchQuery.trim()
        if (query.isEmpty()) return

        if (sourceManager.findSourceForLink(query) != null) {
            parseAndPlay(query)
            return
        }

        isLoading = true
        errorMessage = null
        coroutineScope.launch {
            try {
                val results = sourceManager.search(query)
                isLoading = false
                if (results.isEmpty()) {
                    errorMessage = "未找到与 \"$query\" 相关的音频，请尝试其他关键词"
                } else {
                    searchResults = results
                }
            } catch (e: Exception) {
                isLoading = false
                errorMessage = "搜索失败: ${e.localizedMessage ?: "网络错误"}"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!initialSharedText.isNullOrEmpty()) {
            searchQuery = initialSharedText
            parseAndPlay(initialSharedText)
        } else {
            checkClipboardForLink()
        }
    }

    LaunchedEffect(playerState.playerError) {
        playerState.playerError?.let { err ->
            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        bottomBar = {
            Column {
                if (playerState.currentSong != null) {
                    MiniPlayerBar(
                        playerState = playerState,
                        onBarClick = { showFullPlayer = true },
                        onTogglePlay = { playerManager.togglePlayPause() },
                        onSkipNext = { playerManager.skipNext() }
                    )
                }

                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Explore, contentDescription = "发现") },
                        label = { Text("发现与搜索") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Favorite, contentDescription = "收藏") },
                        label = { Text("我的收藏") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> {
                    DiscoverScreen(
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        onSearch = { performSearch() },
                        onParseClipboardLink = { link ->
                            searchQuery = link
                            parseAndPlay(link)
                        },
                        clipboardDetectedLink = clipboardDetectedLink,
                        onDismissClipboardBanner = { clipboardDetectedLink = null },
                        searchResults = searchResults,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        playerState = playerState,
                        favoritesList = favoritesList,
                        onSongClick = { song ->
                            playerManager.playSong(song, searchResults)
                        },
                        onToggleFavorite = { song ->
                            coroutineScope.launch {
                                localRepo.toggleFavorite(song)
                            }
                        },
                        onPasteFromClipboard = {
                            try {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val item = clipboard?.primaryClip?.getItemAt(0)
                                val pasted = item?.text?.toString() ?: ""
                                if (pasted.isNotEmpty()) {
                                    searchQuery = pasted
                                    if (sourceManager.findSourceForLink(pasted) != null) {
                                        parseAndPlay(pasted)
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    )
                }
                1 -> {
                    FavoritesScreen(
                        favoritesList = favoritesList,
                        playerState = playerState,
                        onSongClick = { song ->
                            playerManager.playSong(song, favoritesList)
                        },
                        onToggleFavorite = { song ->
                            coroutineScope.launch {
                                localRepo.toggleFavorite(song)
                            }
                        },
                        onPlayAll = {
                            if (favoritesList.isNotEmpty()) {
                                playerManager.playSong(favoritesList.first(), favoritesList)
                            }
                        },
                        onShuffleAll = {
                            if (favoritesList.isNotEmpty()) {
                                val shuffled = favoritesList.shuffled()
                                playerManager.playSong(shuffled.first(), shuffled)
                            }
                        },
                        onNavigateToDiscover = {
                            selectedTab = 0
                        }
                    )
                }
            }
        }
    }

    if (showFullPlayer && playerState.currentSong != null) {
        val currentSong = playerState.currentSong!!
        val isFavorited = favoritesList.any { it.id == currentSong.id }

        FullPlayerDialog(
            playerState = playerState,
            isFavorited = isFavorited,
            onDismiss = { showFullPlayer = false },
            onTogglePlay = { playerManager.togglePlayPause() },
            onSeekTo = { pos -> playerManager.seekTo(pos) },
            onSkipNext = { playerManager.skipNext() },
            onSkipPrevious = { playerManager.skipPrevious() },
            onToggleMode = { playerManager.togglePlaybackMode() },
            onToggleFavorite = {
                coroutineScope.launch {
                    localRepo.toggleFavorite(currentSong)
                }
            }
        )
    }
}
