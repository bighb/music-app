package com.personal.musicapp.ui.screens

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.musicapp.data.local.DownloadState
import com.personal.musicapp.data.model.Song
import com.personal.musicapp.player.PlayerState
import com.personal.musicapp.ui.components.SongListItem
import com.personal.musicapp.ui.theme.BorderLight
import com.personal.musicapp.ui.theme.InkBlack
import com.personal.musicapp.ui.theme.RosePrimary
import com.personal.musicapp.ui.theme.RoseTint
import com.personal.musicapp.ui.theme.SlateDark
import com.personal.musicapp.ui.theme.SlateMuted
import com.personal.musicapp.util.AppLogger
import com.personal.musicapp.util.AppToast
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen(
    favoritesList: List<Song>,
    downloadsList: List<Song>,
    playHistoryList: List<Song>,
    playerState: PlayerState,
    downloadStatusMap: Map<String, DownloadState> = emptyMap(),
    selectedSubTab: Int = 0,
    onSubTabChange: (Int) -> Unit = {},
    onSongClick: (Song, List<Song>) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onDownload: (Song) -> Unit = {},
    onDeleteDownload: (Song) -> Unit = {},
    onPlayNext: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onRemoveHistoryItem: (String) -> Unit,
    onClearHistory: () -> Unit,
    onBatchRemoveFavorites: (Set<String>) -> Unit = {},
    onBatchDeleteDownloads: (Set<String>) -> Unit = {},
    onBatchRemoveHistory: (Set<String>) -> Unit = {},
    onPlayAllFavorites: () -> Unit,
    onShuffleAllFavorites: () -> Unit,
    onPlayAllDownloads: () -> Unit,
    onShuffleAllDownloads: () -> Unit,
    onPlayAllHistory: () -> Unit = {},
    onShuffleAllHistory: () -> Unit = {},
    onNavigateToDiscover: () -> Unit,
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val pagerState = rememberPagerState(initialPage = selectedSubTab.coerceIn(0, 2), pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()
    val hasErrorLog by AppLogger.hasErrorFlow.collectAsState()

    val appVersion = remember {
        try {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            "v${pkg.versionName}"
        } catch (_: Exception) {
            "v1.3.0"
        }
    }

    // 3-tap secret trigger for DebugLogDialog
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }

    // Filter & Batch Selection states (auto-reset on tab switch)
    var showSearchBar by remember { mutableStateOf(false) }
    var filterQuery by remember { mutableStateOf("") }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedSongIds by remember { mutableStateOf(setOf<String>()) }

    // High-risk confirmation dialogs
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(selectedSubTab) {
        if (pagerState.currentPage != selectedSubTab) {
            pagerState.animateScrollToPage(selectedSubTab)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        onSubTabChange(pagerState.currentPage)
        // Reset batch selection & filter when switching between Favorites / Downloads / History
        isSelectionMode = false
        selectedSongIds = emptySet()
        filterQuery = ""
        showSearchBar = false
        focusManager.clearFocus()
    }

    val currentActiveList = when (pagerState.currentPage) {
        0 -> favoritesList
        1 -> downloadsList
        else -> playHistoryList
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        // Top Header: Title + Subtle Version Pill (3-tap to open Debug Log) + Filter & Batch Action Icons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTapTimeMs > 1200L) {
                        versionTapCount = 1
                    } else {
                        versionTapCount++
                    }
                    lastTapTimeMs = now
                    if (versionTapCount >= 3) {
                        versionTapCount = 0
                        onOpenDebug()
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = RosePrimary,
                    modifier = Modifier.size(25.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "我的音乐",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = InkBlack
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Subtle version badge (shows tiny error dot if logs have error)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = appVersion,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = SlateMuted
                    )
                    if (hasErrorLog) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error)
                        )
                    }
                }
            }

            // Quick Filter & Batch Management entry buttons
            if (currentActiveList.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            showSearchBar = !showSearchBar
                            if (!showSearchBar) {
                                filterQuery = ""
                                focusManager.clearFocus()
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "列表内搜索过滤",
                            tint = if (showSearchBar || filterQuery.isNotEmpty()) RosePrimary else SlateDark,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            isSelectionMode = !isSelectionMode
                            if (!isSelectionMode) selectedSongIds = emptySet()
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isSelectionMode) Icons.Default.Close else Icons.Default.Checklist,
                            contentDescription = if (isSelectionMode) "退出管理" else "批量管理",
                            tint = if (isSelectionMode) RosePrimary else SlateDark,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 1. Pill Segmented Control (replaces traditional underline TabRow)
        PillSegmentedControl(
            selectedIndex = pagerState.currentPage,
            items = listOf(
                SegmentItem("收藏", favoritesList.size),
                SegmentItem("下载", downloadsList.size),
                SegmentItem("历史", playHistoryList.size)
            ),
            onSelect = { idx ->
                coroutineScope.launch { pagerState.animateScrollToPage(idx) }
            }
        )

        // Collapsible In-List Filter Bar
        AnimatedVisibility(
            visible = showSearchBar && currentActiveList.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, if (filterQuery.isNotEmpty()) RosePrimary.copy(alpha = 0.4f) else BorderLight)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = SlateMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (filterQuery.isEmpty()) {
                            Text(
                                text = "在当前列表中筛选歌名或歌手...",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = SlateMuted,
                                    fontSize = 12.sp
                                )
                            )
                        }
                        BasicTextField(
                            value = filterQuery,
                            onValueChange = { filterQuery = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = InkBlack,
                                fontSize = 13.sp
                            ),
                            cursorBrush = SolidColor(RosePrimary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (filterQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { filterQuery = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "清除筛选",
                                tint = SlateMuted,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }

        // Collapsible Batch Management Action Bar
        AnimatedVisibility(
            visible = isSelectionMode && currentActiveList.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val filteredCurrentIds = remember(currentActiveList, filterQuery) {
                currentActiveList.filter {
                    filterQuery.isBlank() ||
                            it.title.contains(filterQuery, ignoreCase = true) ||
                            it.artist.contains(filterQuery, ignoreCase = true)
                }.map { it.id }.toSet()
            }
            val isAllSelected = filteredCurrentIds.isNotEmpty() && selectedSongIds.containsAll(filteredCurrentIds)

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                shape = RoundedCornerShape(12.dp),
                color = RoseTint,
                border = BorderStroke(1.dp, RosePrimary.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                selectedSongIds = if (isAllSelected) {
                                    selectedSongIds - filteredCurrentIds
                                } else {
                                    selectedSongIds + filteredCurrentIds
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = RosePrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isAllSelected) "取消全选" else "全选 (${filteredCurrentIds.size})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = RosePrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "已选 ${selectedSongIds.size} 首",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = SlateDark,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val actionLabel = when (pagerState.currentPage) {
                            0 -> "移出收藏"
                            1 -> "删除离线"
                            else -> "移除记录"
                        }
                        Button(
                            enabled = selectedSongIds.isNotEmpty(),
                            onClick = {
                                if (pagerState.currentPage == 1) {
                                    // Deleting offline files is high-risk -> confirm first
                                    showBatchDeleteConfirm = true
                                } else if (pagerState.currentPage == 0) {
                                    val count = selectedSongIds.size
                                    onBatchRemoveFavorites(selectedSongIds)
                                    AppToast.show(context, "已批量移出 $count 首收藏歌曲")
                                    selectedSongIds = emptySet()
                                    isSelectionMode = false
                                } else {
                                    val count = selectedSongIds.size
                                    onBatchRemoveHistory(selectedSongIds)
                                    AppToast.show(context, "已移除 $count 条播放记录")
                                    selectedSongIds = emptySet()
                                    isSelectionMode = false
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(actionLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
                0 -> {
                    // ==================== TAB 0: 我的收藏 ====================
                    val filteredFavorites = remember(favoritesList, filterQuery) {
                        if (filterQuery.isBlank()) favoritesList
                        else favoritesList.filter {
                            it.title.contains(filterQuery, ignoreCase = true) ||
                                    it.artist.contains(filterQuery, ignoreCase = true)
                        }
                    }

                    if (favoritesList.isNotEmpty()) {
                        val listState = rememberLazyListState()
                        val isCompactHeader by remember {
                            derivedStateOf { listState.firstVisibleItemIndex >= 1 }
                        }
                        val offlineCount = remember(favoritesList, downloadsList) {
                            favoritesList.count { song ->
                                downloadsList.any { it.id == song.id } ||
                                        (song.localAudioPath != null && java.io.File(song.localAudioPath).exists())
                            }
                        }

                        Column(modifier = Modifier.fillMaxSize()) {
                            // Sticky / Collapsible Summary Card with Split Pill Button & Offline Coverage Progress
                            LibrarySummaryCard(
                                title = "共 ${favoritesList.size} 首收藏歌曲",
                                subtitle = "已离线 $offlineCount / ${favoritesList.size} 首",
                                progressRatio = if (favoritesList.isNotEmpty()) offlineCount.toFloat() / favoritesList.size else 0f,
                                isCompact = isCompactHeader,
                                onPlayAll = {
                                    if (filteredFavorites.isNotEmpty()) {
                                        onSongClick(filteredFavorites.first(), filteredFavorites)
                                    } else {
                                        onPlayAllFavorites()
                                    }
                                },
                                onShuffleAll = {
                                    if (filteredFavorites.isNotEmpty()) {
                                        val shuffled = filteredFavorites.shuffled()
                                        onSongClick(shuffled.first(), shuffled)
                                    } else {
                                        onShuffleAllFavorites()
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            if (filteredFavorites.isEmpty()) {
                                FilterEmptyPlaceholder(filterQuery = filterQuery, onClearFilter = { filterQuery = "" })
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    contentPadding = PaddingValues(bottom = 120.dp)
                                ) {
                                    items(filteredFavorites, key = { it.id }) { song ->
                                        val isCurrent = playerState.currentSong?.id == song.id
                                        val isActivelyPlaying = isCurrent && playerState.isPlaying
                                        val isBuffering = isCurrent && playerState.isBuffering
                                        val hasError = isCurrent && playerState.playerError != null
                                        val isDownloaded = downloadsList.any { it.id == song.id } ||
                                                (song.localAudioPath != null && java.io.File(song.localAudioPath).exists())
                                        val isDownloading = downloadStatusMap[song.id] is DownloadState.Downloading

                                        SongListItem(
                                            song = song,
                                            isPlaying = isCurrent,
                                            isActivelyPlaying = isActivelyPlaying,
                                            isFavorited = true,
                                            isBuffering = isBuffering,
                                            hasError = hasError,
                                            isDownloaded = isDownloaded,
                                            isDownloading = isDownloading,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = song.id in selectedSongIds,
                                            onToggleSelect = {
                                                selectedSongIds = if (song.id in selectedSongIds) {
                                                    selectedSongIds - song.id
                                                } else {
                                                    selectedSongIds + song.id
                                                }
                                            },
                                            onClick = { onSongClick(song, filteredFavorites) },
                                            onToggleFavorite = { onToggleFavorite(song) },
                                            onDownload = { onDownload(song) },
                                            onDeleteDownload = { onDeleteDownload(song) },
                                            onPlayNext = { onPlayNext(song) },
                                            onAddToPlaylist = { onAddToPlaylist(song) },
                                            onRemove = { onToggleFavorite(song) }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        SoftGlowEmptyState(
                            icon = Icons.Outlined.FavoriteBorder,
                            iconTint = RosePrimary,
                            title = "暂无收藏歌曲",
                            description = "在播放页或歌曲菜单中轻触爱心即可收藏心仪歌曲，方便随时回味",
                            actionLabel = "去发现好音乐",
                            onAction = onNavigateToDiscover
                        )
                    }
                }

                1 -> {
                    // ==================== TAB 1: 我的下载 ====================
                    val filteredDownloads = remember(downloadsList, filterQuery) {
                        if (filterQuery.isBlank()) downloadsList
                        else downloadsList.filter {
                            it.title.contains(filterQuery, ignoreCase = true) ||
                                    it.artist.contains(filterQuery, ignoreCase = true)
                        }
                    }

                    if (downloadsList.isNotEmpty()) {
                        val listState = rememberLazyListState()
                        val isCompactHeader by remember {
                            derivedStateOf { listState.firstVisibleItemIndex >= 1 }
                        }

                        Column(modifier = Modifier.fillMaxSize()) {
                            LibrarySummaryCard(
                                title = "共 ${downloadsList.size} 首离线歌曲",
                                subtitle = "100% 本地存储 · 断网脱机随时畅听",
                                progressRatio = 1f,
                                isCompact = isCompactHeader,
                                onPlayAll = {
                                    if (filteredDownloads.isNotEmpty()) {
                                        onSongClick(filteredDownloads.first(), filteredDownloads)
                                    } else {
                                        onPlayAllDownloads()
                                    }
                                },
                                onShuffleAll = {
                                    if (filteredDownloads.isNotEmpty()) {
                                        val shuffled = filteredDownloads.shuffled()
                                        onSongClick(shuffled.first(), shuffled)
                                    } else {
                                        onShuffleAllDownloads()
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            if (filteredDownloads.isEmpty()) {
                                FilterEmptyPlaceholder(filterQuery = filterQuery, onClearFilter = { filterQuery = "" })
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    contentPadding = PaddingValues(bottom = 120.dp)
                                ) {
                                    items(filteredDownloads, key = { "download_${it.id}" }) { song ->
                                        val isCurrent = playerState.currentSong?.id == song.id
                                        val isActivelyPlaying = isCurrent && playerState.isPlaying
                                        val isBuffering = isCurrent && playerState.isBuffering
                                        val hasError = isCurrent && playerState.playerError != null
                                        val isFavorited = favoritesList.any { it.id == song.id }

                                        SongListItem(
                                            song = song,
                                            isPlaying = isCurrent,
                                            isActivelyPlaying = isActivelyPlaying,
                                            isFavorited = isFavorited,
                                            isBuffering = isBuffering,
                                            hasError = hasError,
                                            isDownloaded = true,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = song.id in selectedSongIds,
                                            onToggleSelect = {
                                                selectedSongIds = if (song.id in selectedSongIds) {
                                                    selectedSongIds - song.id
                                                } else {
                                                    selectedSongIds + song.id
                                                }
                                            },
                                            onClick = { onSongClick(song, filteredDownloads) },
                                            onToggleFavorite = { onToggleFavorite(song) },
                                            onDeleteDownload = { onDeleteDownload(song) },
                                            onPlayNext = { onPlayNext(song) },
                                            onAddToPlaylist = { onAddToPlaylist(song) },
                                            onRemove = { onDeleteDownload(song) }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        SoftGlowEmptyState(
                            icon = Icons.Outlined.CloudDone,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            title = "暂无离线下载歌曲",
                            description = "在播放页或歌曲菜单中选择「下载到本地离线」，无网络也能自在听歌",
                            actionLabel = "去搜搜好歌",
                            onAction = onNavigateToDiscover
                        )
                    }
                }

                2 -> {
                    // ==================== TAB 2: 播放历史 ====================
                    val filteredHistory = remember(playHistoryList, filterQuery) {
                        if (filterQuery.isBlank()) playHistoryList
                        else playHistoryList.filter {
                            it.title.contains(filterQuery, ignoreCase = true) ||
                                    it.artist.contains(filterQuery, ignoreCase = true)
                        }
                    }

                    if (playHistoryList.isNotEmpty()) {
                        val listState = rememberLazyListState()
                        val isCompactHeader by remember {
                            derivedStateOf { listState.firstVisibleItemIndex >= 1 }
                        }

                        Column(modifier = Modifier.fillMaxSize()) {
                            LibrarySummaryCard(
                                title = "共 ${playHistoryList.size} 首播放记录",
                                subtitle = "自动记录最近听过的歌曲",
                                progressRatio = null,
                                isCompact = isCompactHeader,
                                onPlayAll = {
                                    if (filteredHistory.isNotEmpty()) {
                                        onSongClick(filteredHistory.first(), filteredHistory)
                                    } else {
                                        onPlayAllHistory()
                                    }
                                },
                                onShuffleAll = {
                                    if (filteredHistory.isNotEmpty()) {
                                        val shuffled = filteredHistory.shuffled()
                                        onSongClick(shuffled.first(), shuffled)
                                    } else {
                                        onShuffleAllHistory()
                                    }
                                },
                                onClearAction = { showClearHistoryConfirm = true }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            if (filteredHistory.isEmpty()) {
                                FilterEmptyPlaceholder(filterQuery = filterQuery, onClearFilter = { filterQuery = "" })
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    contentPadding = PaddingValues(bottom = 120.dp)
                                ) {
                                    items(filteredHistory, key = { "history_${it.id}" }) { song ->
                                        val isCurrent = playerState.currentSong?.id == song.id
                                        val isActivelyPlaying = isCurrent && playerState.isPlaying
                                        val isBuffering = isCurrent && playerState.isBuffering
                                        val hasError = isCurrent && playerState.playerError != null
                                        val isFavorited = favoritesList.any { it.id == song.id }
                                        val isDownloaded = downloadsList.any { it.id == song.id } ||
                                                (song.localAudioPath != null && java.io.File(song.localAudioPath).exists())
                                        val isDownloading = downloadStatusMap[song.id] is DownloadState.Downloading

                                        SongListItem(
                                            song = song,
                                            isPlaying = isCurrent,
                                            isActivelyPlaying = isActivelyPlaying,
                                            isFavorited = isFavorited,
                                            isBuffering = isBuffering,
                                            hasError = hasError,
                                            isDownloaded = isDownloaded,
                                            isDownloading = isDownloading,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = song.id in selectedSongIds,
                                            onToggleSelect = {
                                                selectedSongIds = if (song.id in selectedSongIds) {
                                                    selectedSongIds - song.id
                                                } else {
                                                    selectedSongIds + song.id
                                                }
                                            },
                                            onClick = { onSongClick(song, filteredHistory) },
                                            onToggleFavorite = { onToggleFavorite(song) },
                                            onDownload = { onDownload(song) },
                                            onDeleteDownload = { onDeleteDownload(song) },
                                            onPlayNext = { onPlayNext(song) },
                                            onAddToPlaylist = { onAddToPlaylist(song) },
                                            onRemove = { onRemoveHistoryItem(song.id) }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        SoftGlowEmptyState(
                            icon = Icons.Default.History,
                            iconTint = RosePrimary,
                            title = "暂无播放历史",
                            description = "听过的歌曲会自动记录在这里，方便你随时重温旋律",
                            actionLabel = "去发现好音乐",
                            onAction = onNavigateToDiscover
                        )
                    }
                }
            }
        }
    }

    // Confirmation Dialog for Clearing Play History
    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = {
                Text(
                    text = "清空播放历史",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    text = "确定要清空全部 ${playHistoryList.size} 条播放历史记录吗？此操作无法撤销。",
                    style = MaterialTheme.typography.bodyMedium.copy(color = SlateDark)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearHistoryConfirm = false
                        onClearHistory()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("确认清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) {
                    Text("取消", color = SlateDark)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Confirmation Dialog for Batch Deleting Offline Downloads
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = {
                Text(
                    text = "批量删除离线文件",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    text = "确定要删除所选 ${selectedSongIds.size} 首歌曲的本地离线缓存文件吗？",
                    style = MaterialTheme.typography.bodyMedium.copy(color = SlateDark)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val count = selectedSongIds.size
                        onBatchDeleteDownloads(selectedSongIds)
                        AppToast.show(context, "已删除 $count 首本地离线歌曲")
                        selectedSongIds = emptySet()
                        isSelectionMode = false
                        showBatchDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text("取消", color = SlateDark)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

private data class SegmentItem(val label: String, val count: Int)

@Composable
private fun PillSegmentedControl(
    selectedIndex: Int,
    items: List<SegmentItem>,
    onSelect: (Int) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (isSelected) {
                                Modifier.shadow(2.dp, RoundedCornerShape(10.dp))
                            } else Modifier
                        )
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) InkBlack else SlateMuted,
                                fontSize = 13.sp
                            )
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isSelected) RosePrimary.copy(alpha = 0.14f)
                                    else SlateMuted.copy(alpha = 0.12f)
                                )
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = item.count.toString(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) RosePrimary else SlateMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySummaryCard(
    title: String,
    subtitle: String,
    progressRatio: Float?,
    isCompact: Boolean,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onClearAction: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(1.dp, RosePrimary.copy(alpha = 0.14f)),
                RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            RoseTint,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                    )
                )
                .padding(horizontal = 14.dp, vertical = if (isCompact) 8.dp else 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = InkBlack,
                                fontSize = if (isCompact) 13.sp else 15.sp
                            )
                        )
                        if (onClearAction != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                onClick = onClearAction,
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "清空历史",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = "清空",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = !isCompact) {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (progressRatio != null) {
                                    Icon(
                                        imageVector = Icons.Outlined.CloudDone,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = SlateMuted,
                                        fontSize = 11.sp
                                    )
                                )
                            }

                            if (progressRatio != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                // Visual Capsule Progress Bar for Offline Coverage
                                Box(
                                    modifier = Modifier
                                        .width(120.dp)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(BorderLight)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(progressRatio.coerceIn(0f, 1f))
                                            .clip(RoundedCornerShape(50))
                                            .background(MaterialTheme.colorScheme.tertiary)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Unified Split Pill Button: [ ▶ 播放 | 🔀 ]
                Surface(
                    shape = RoundedCornerShape(50),
                    color = RosePrimary,
                    shadowElevation = 2.dp,
                    modifier = Modifier.height(36.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clickable(onClick = onPlayAll)
                                .padding(start = 14.dp, end = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "播放全部",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "播放",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Subtle 1dp vertical divider inside split pill
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(18.dp)
                                .background(Color.White.copy(alpha = 0.28f))
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clickable(onClick = onShuffleAll)
                                .padding(start = 10.dp, end = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "随机播放",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterEmptyPlaceholder(
    filterQuery: String,
    onClearFilter: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = SlateMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "未找到包含 \"$filterQuery\" 的歌曲",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = SlateDark,
                    fontWeight = FontWeight.Medium
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onClearFilter) {
                Text("清除筛选条件", color = RosePrimary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SoftGlowEmptyState(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // Soft Duotone Glow Disc (replaces harsh 72dp solid gray block)
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                iconTint.copy(alpha = 0.16f),
                                iconTint.copy(alpha = 0.04f),
                                Color.Transparent
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(iconTint.copy(alpha = 0.1f))
                        .border(1.dp, iconTint.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = InkBlack
                )
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = SlateMuted,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onAction,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RosePrimary,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Text(actionLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}
