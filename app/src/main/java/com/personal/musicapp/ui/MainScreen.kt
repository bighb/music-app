package com.personal.musicapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import com.personal.musicapp.MusicApplication
import com.personal.musicapp.data.local.DownloadState
import com.personal.musicapp.data.model.Song
import com.personal.musicapp.ui.components.DebugLogDialog
import com.personal.musicapp.ui.components.FullPlayerDialog
import com.personal.musicapp.ui.components.MiniPlayerBar
import com.personal.musicapp.ui.components.PlaylistBottomSheet
import com.personal.musicapp.ui.screens.DiscoverScreen
import com.personal.musicapp.ui.screens.FavoritesScreen
import com.personal.musicapp.util.AppLogger
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    initialSharedText: String? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val app = MusicApplication.instance
    val playerManager = app.playerManager
    val localRepo = app.localRepository
    val sourceManager = app.audioSourceManager

    val playerState by playerManager.playerState.collectAsState()
    val favoritesList by localRepo.favorites.collectAsState()
    val downloadsList by localRepo.downloads.collectAsState()
    val downloadStatusMap by localRepo.downloadStates.collectAsState()
    val playHistoryList by localRepo.playHistory.collectAsState()
    val searchHistory by localRepo.searchHistory.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showFullPlayer by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showDebugDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun parseAndPlay(linkOrText: String) {
        isLoading = true
        errorMessage = null
        AppLogger.i("MainScreen", "Parsing link: $linkOrText")
        coroutineScope.launch {
            try {
                val song = sourceManager.parseShareLink(linkOrText)
                isLoading = false
                if (song != null) {
                    searchResults = listOf(song)
                    playerManager.playSong(song, listOf(song))
                    snackbarHostState.showSnackbar("解析成功: 《${song.title}》")
                } else {
                    errorMessage = "未能解析到有效音频，请检查链接后重试"
                    AppLogger.w("MainScreen", "Failed to parse link: $linkOrText")
                    snackbarHostState.showSnackbar(errorMessage!!)
                }
            } catch (e: Exception) {
                isLoading = false
                errorMessage = "解析发生错误: ${e.localizedMessage ?: "网络错误"}"
                AppLogger.e("MainScreen", "Parse link error", e)
                snackbarHostState.showSnackbar(errorMessage!!)
            }
        }
    }

    fun performSearch(queryToSearch: String = searchQuery) {
        val query = queryToSearch.trim()
        if (query.isEmpty()) return

        // Save to search history
        localRepo.addSearchQuery(query)

        if (sourceManager.findSourceForLink(query) != null || query.contains("http://") || query.contains("https://")) {
            parseAndPlay(query)
            return
        }

        isLoading = true
        errorMessage = null
        AppLogger.i("MainScreen", "Searching: $query")
        coroutineScope.launch {
            try {
                val results = sourceManager.search(query)
                isLoading = false
                if (results.isEmpty()) {
                    errorMessage = "未找到与 \"$query\" 相关的音频，请尝试其他关键词"
                    AppLogger.w("MainScreen", "No search results for: $query")
                } else {
                    searchResults = results
                    AppLogger.i("MainScreen", "Found ${results.size} results for: $query")
                }
            } catch (e: Exception) {
                isLoading = false
                errorMessage = "搜索失败: ${e.localizedMessage ?: "网络错误"}"
                AppLogger.e("MainScreen", "Search error for $query", e)
            }
        }
    }

    fun handleToggleFavorite(song: Song) {
        val willBeFavorited = !favoritesList.any { it.id == song.id }
        coroutineScope.launch {
            localRepo.toggleFavorite(song)
            if (willBeFavorited) {
                snackbarHostState.showSnackbar("已添加到收藏")
            } else {
                snackbarHostState.showSnackbar("已从收藏移除")
            }
        }
    }

    fun handleDownload(song: Song) {
        coroutineScope.launch {
            snackbarHostState.showSnackbar("开始下载《${song.title}》...")
            localRepo.downloadSong(song) { success, errorMsg ->
                coroutineScope.launch {
                    if (success) {
                        snackbarHostState.showSnackbar("已下载完成: 《${song.title}》")
                    } else {
                        snackbarHostState.showSnackbar("下载失败: ${errorMsg ?: "网络异常"}")
                    }
                }
            }
        }
    }

    fun handleDeleteDownload(song: Song) {
        coroutineScope.launch {
            localRepo.deleteDownload(song.id)
            snackbarHostState.showSnackbar("已删除《${song.title}》的本地离线文件")
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
                actionLabel = "查看日志",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                showDebugDialog = true
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
                        onSkipNext = { playerManager.skipNext() },
                        onOpenQueue = { showQueueSheet = true }
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
                        icon = { Icon(Icons.Default.Favorite, contentDescription = "音乐库") },
                        label = { Text("音乐库") }
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
                        searchResults = searchResults,
                        searchHistory = searchHistory,
                        onSelectHistoryQuery = { q ->
                            searchQuery = q
                            performSearch(q)
                        },
                        onDeleteHistoryQuery = { q ->
                            localRepo.removeSearchQuery(q)
                        },
                        onClearSearchHistory = {
                            localRepo.clearSearchHistory()
                        },
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        playerState = playerState,
                        favoritesList = favoritesList,
                        downloadStatusMap = downloadStatusMap,
                        onSongClick = { song ->
                            playerManager.playSong(song, searchResults)
                        },
                        onDownload = { song ->
                            handleDownload(song)
                        },
                        onDeleteDownload = { song ->
                            handleDeleteDownload(song)
                        },
                        onPlayNext = { song ->
                            playerManager.playNext(song)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("已设为下一首播放: 《${song.title}》")
                            }
                        },
                        onAddToPlaylist = { song ->
                            playerManager.addToPlaylist(song)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("已加入播放队列: 《${song.title}》")
                            }
                        },
                        onOpenDebug = {
                            showDebugDialog = true
                        }
                    )
                }
                1 -> {
                    FavoritesScreen(
                        favoritesList = favoritesList,
                        downloadsList = downloadsList,
                        playHistoryList = playHistoryList,
                        playerState = playerState,
                        downloadStatusMap = downloadStatusMap,
                        onSongClick = { song ->
                            val currentList = when {
                                downloadsList.any { it.id == song.id } -> downloadsList
                                favoritesList.any { it.id == song.id } -> favoritesList
                                else -> playHistoryList
                            }
                            playerManager.playSong(song, currentList)
                        },
                        onToggleFavorite = { song ->
                            handleToggleFavorite(song)
                        },
                        onDownload = { song ->
                            handleDownload(song)
                        },
                        onDeleteDownload = { song ->
                            handleDeleteDownload(song)
                        },
                        onPlayNext = { song ->
                            playerManager.playNext(song)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("已设为下一首播放: 《${song.title}》")
                            }
                        },
                        onAddToPlaylist = { song ->
                            playerManager.addToPlaylist(song)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("已加入播放队列: 《${song.title}》")
                            }
                        },
                        onRemoveHistoryItem = { songId ->
                            localRepo.removePlayHistoryItem(songId)
                        },
                        onClearHistory = {
                            localRepo.clearPlayHistory()
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("播放历史已清空")
                            }
                        },
                        onPlayAllFavorites = {
                            if (favoritesList.isNotEmpty()) {
                                playerManager.playSong(favoritesList.first(), favoritesList)
                            }
                        },
                        onShuffleAllFavorites = {
                            if (favoritesList.isNotEmpty()) {
                                val shuffled = favoritesList.shuffled()
                                playerManager.playSong(shuffled.first(), shuffled)
                            }
                        },
                        onPlayAllDownloads = {
                            if (downloadsList.isNotEmpty()) {
                                playerManager.playSong(downloadsList.first(), downloadsList)
                            }
                        },
                        onShuffleAllDownloads = {
                            if (downloadsList.isNotEmpty()) {
                                val shuffled = downloadsList.shuffled()
                                playerManager.playSong(shuffled.first(), shuffled)
                            }
                        },
                        onNavigateToDiscover = {
                            selectedTab = 0
                        },
                        onOpenDebug = {
                            showDebugDialog = true
                        }
                    )
                }
            }
        }
    }

    if (showFullPlayer && playerState.currentSong != null) {
        val currentSong = playerState.currentSong!!
        val isFavorited = favoritesList.any { it.id == currentSong.id }
        val isDownloaded = localRepo.isDownloaded(currentSong.id)
        val isDownloading = downloadStatusMap[currentSong.id] is DownloadState.Downloading

        FullPlayerDialog(
            playerState = playerState,
            isFavorited = isFavorited,
            isDownloaded = isDownloaded,
            isDownloading = isDownloading,
            onDismiss = { showFullPlayer = false },
            onTogglePlay = { playerManager.togglePlayPause() },
            onSeekTo = { pos -> playerManager.seekTo(pos) },
            onSkipNext = { playerManager.skipNext() },
            onSkipPrevious = { playerManager.skipPrevious() },
            onToggleMode = { playerManager.togglePlaybackMode() },
            onToggleFavorite = {
                handleToggleFavorite(currentSong)
            },
            onDownload = {
                handleDownload(currentSong)
            },
            onOpenQueue = {
                showQueueSheet = true
            }
        )
    }

    if (showQueueSheet) {
        PlaylistBottomSheet(
            playerState = playerState,
            playHistoryList = playHistoryList,
            onDismiss = { showQueueSheet = false },
            onSelectSong = { song ->
                playerManager.playSong(song, playerState.playlist)
            },
            onRemoveFromQueue = { songId ->
                val removedTitle = playerState.playlist.find { it.id == songId }?.title ?: ""
                playerManager.removeFromPlaylist(songId)
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("已从队列移除: 《$removedTitle》")
                }
            },
            onReorderQueue = { from, to ->
                playerManager.reorderPlaylist(from, to)
            },
            onClearQueue = {
                playerManager.clearPlaylist()
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("播放队列已清空")
                }
            },
            onClearHistory = {
                localRepo.clearPlayHistory()
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("播放历史已清空")
                }
            },
            onAddToQueue = { song ->
                playerManager.addToPlaylist(song)
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("已加入播放队列: 《${song.title}》")
                }
            }
        )
    }

    if (showDebugDialog) {
        DebugLogDialog(
            onDismiss = { showDebugDialog = false }
        )
    }
}
