package com.personal.musicapp.ui

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
    val snackbarHostState = remember { SnackbarHostState() }

    fun parseAndPlay(linkOrText: String) {
        isLoading = true
        errorMessage = null
        coroutineScope.launch {
            try {
                val song = sourceManager.parseShareLink(linkOrText)
                isLoading = false
                if (song != null) {
                    searchResults = listOf(song)
                    playerManager.playSong(song, listOf(song))
                    snackbarHostState.showSnackbar("解析成功: 《${song.title}》")
                } else {
                    errorMessage = "未能解析到有效音频，请检查链接是否有效后重试"
                    snackbarHostState.showSnackbar(errorMessage!!)
                }
            } catch (e: Exception) {
                isLoading = false
                errorMessage = "解析发生错误: ${e.localizedMessage ?: "未知网络错误"}"
                snackbarHostState.showSnackbar(errorMessage!!)
            }
        }
    }

    fun performSearch() {
        val query = searchQuery.trim()
        if (query.isEmpty()) return

        if (sourceManager.findSourceForLink(query) != null || query.contains("http://") || query.contains("https://")) {
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

    fun handlePasteAndParse() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipData = clipboard?.primaryClip
            if (clipData == null || clipData.itemCount == 0) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("剪切板中暂无内容，请先复制短视频或音乐分享链接")
                }
                return
            }

            val pastedText = clipData.getItemAt(0)?.text?.toString()?.trim() ?: ""
            if (pastedText.isEmpty()) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("剪切板内容为空")
                }
                return
            }

            searchQuery = pastedText
            if (sourceManager.findSourceForLink(pastedText) != null || pastedText.contains("http://") || pastedText.contains("https://")) {
                parseAndPlay(pastedText)
            } else {
                performSearch()
            }
        } catch (e: Exception) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("读取剪切板失败: ${e.localizedMessage ?: "权限错误"}")
            }
        }
    }

    fun handleToggleFavorite(song: Song) {
        val willBeFavorited = !favoritesList.any { it.id == song.id }
        coroutineScope.launch {
            localRepo.toggleFavorite(song)
            if (willBeFavorited) {
                snackbarHostState.showSnackbar("已收藏《${song.title}》，正在后台离线缓存（支持无网畅听）")
            } else {
                snackbarHostState.showSnackbar("已从收藏移除，已自动清理本地离线缓存")
            }
        }
    }

    // Only triggered if shared directly into app from external share sheet
    LaunchedEffect(Unit) {
        if (!initialSharedText.isNullOrEmpty()) {
            searchQuery = initialSharedText
            parseAndPlay(initialSharedText)
        }
    }

    // Show actionable Snackbar on playback failure
    LaunchedEffect(playerState.playerError) {
        playerState.playerError?.let { err ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = err,
                actionLabel = if (playerState.playlist.size > 1) "下一首" else "我知道了",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed && playerState.playlist.size > 1) {
                playerManager.skipNext()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        onPasteAndParse = { handlePasteAndParse() },
                        searchResults = searchResults,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        playerState = playerState,
                        favoritesList = favoritesList,
                        onSongClick = { song ->
                            playerManager.playSong(song, searchResults)
                        },
                        onToggleFavorite = { song ->
                            handleToggleFavorite(song)
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
                            handleToggleFavorite(song)
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
                handleToggleFavorite(currentSong)
            }
        )
    }
}
