package com.personal.musicapp.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.musicapp.data.local.DownloadState
import com.personal.musicapp.data.model.Song
import com.personal.musicapp.player.PlayerState
import com.personal.musicapp.ui.components.SongListItem
import com.personal.musicapp.ui.theme.InkBlack
import com.personal.musicapp.ui.theme.RosePrimary
import com.personal.musicapp.ui.theme.SlateDark
import com.personal.musicapp.ui.theme.SlateMuted
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
    onPlayAllFavorites: () -> Unit,
    onShuffleAllFavorites: () -> Unit,
    onPlayAllDownloads: () -> Unit,
    onShuffleAllDownloads: () -> Unit,
    onNavigateToDiscover: () -> Unit,
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(initialPage = selectedSubTab.coerceIn(0, 2), pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(selectedSubTab) {
        if (pagerState.currentPage != selectedSubTab) {
            pagerState.animateScrollToPage(selectedSubTab)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        onSubTabChange(pagerState.currentPage)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Title Row without Debug Icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = RosePrimary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "我的",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = InkBlack
                )
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Sub Tabs: [我的收藏] vs [我的下载] vs [播放历史]
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
            contentColor = RosePrimary
        ) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                text = {
                    Text(
                        text = "我的收藏 (${favoritesList.size})",
                        fontWeight = if (pagerState.currentPage == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (pagerState.currentPage == 0) RosePrimary else SlateDark
                    )
                }
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                text = {
                    Text(
                        text = "我的下载 (${downloadsList.size})",
                        fontWeight = if (pagerState.currentPage == 1) FontWeight.Bold else FontWeight.Normal,
                        color = if (pagerState.currentPage == 1) RosePrimary else SlateDark
                    )
                }
            )
            Tab(
                selected = pagerState.currentPage == 2,
                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } },
                text = {
                    Text(
                        text = "播放历史 (${playHistoryList.size})",
                        fontWeight = if (pagerState.currentPage == 2) FontWeight.Bold else FontWeight.Normal,
                        color = if (pagerState.currentPage == 2) RosePrimary else SlateDark
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
            0 -> {
                // ==================== TAB 0: 我的收藏 ====================
                if (favoritesList.isNotEmpty()) {
                    val offlineCount = favoritesList.count { song ->
                        downloadsList.any { it.id == song.id } || (song.localAudioPath != null && java.io.File(song.localAudioPath).exists())
                    }

                    // Summary Card
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "共 ${favoritesList.size} 首收藏歌曲",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CloudDone,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "其中 $offlineCount 首已离线缓存",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }

                            Row {
                                Button(
                                    onClick = onPlayAllFavorites,
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("播放", fontSize = 13.sp)
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                OutlinedButton(
                                    onClick = onShuffleAllFavorites,
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 90.dp)
                    ) {
                        items(favoritesList, key = { it.id }) { song ->
                            val isCurrent = playerState.currentSong?.id == song.id
                            val isBuffering = isCurrent && playerState.isBuffering
                            val hasError = isCurrent && playerState.playerError != null
                            val isDownloaded = downloadsList.any { it.id == song.id } || (song.localAudioPath != null && java.io.File(song.localAudioPath).exists())
                            val isDownloading = downloadStatusMap[song.id] is DownloadState.Downloading

                            SongListItem(
                                song = song,
                                isPlaying = isCurrent,
                                isFavorited = true,
                                isBuffering = isBuffering,
                                hasError = hasError,
                                isDownloaded = isDownloaded,
                                isDownloading = isDownloading,
                                onClick = { onSongClick(song, favoritesList) },
                                onDownload = { onDownload(song) },
                                onDeleteDownload = { onDeleteDownload(song) },
                                onPlayNext = { onPlayNext(song) },
                                onAddToPlaylist = { onAddToPlaylist(song) },
                                onRemove = { onToggleFavorite(song) }
                            )
                        }
                    }
                } else {
                    // Empty State for Favorites
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = Color(0xFFFF2D55),
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "暂无收藏歌曲",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "在播放详情页中点击爱心即可收藏心仪歌曲，方便随时回味！",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 20.sp
                                )
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = onNavigateToDiscover) {
                                Text("去搜搜好歌")
                            }
                        }
                    }
                }
            }

            1 -> {
                // ==================== TAB 1: 我的下载 ====================
                if (downloadsList.isNotEmpty()) {
                    // Summary Card
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "共 ${downloadsList.size} 首离线歌曲",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CloudDone,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "已存储在手机本地，完全支持断网脱机畅听",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }

                            Row {
                                Button(
                                    onClick = onPlayAllDownloads,
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("播放", fontSize = 13.sp)
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                OutlinedButton(
                                    onClick = onShuffleAllDownloads,
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 90.dp)
                    ) {
                        items(downloadsList, key = { "download_${it.id}" }) { song ->
                            val isCurrent = playerState.currentSong?.id == song.id
                            val isBuffering = isCurrent && playerState.isBuffering
                            val hasError = isCurrent && playerState.playerError != null
                            val isFavorited = favoritesList.any { it.id == song.id }

                            SongListItem(
                                song = song,
                                isPlaying = isCurrent,
                                isFavorited = isFavorited,
                                isBuffering = isBuffering,
                                hasError = hasError,
                                isDownloaded = true,
                                onClick = { onSongClick(song, downloadsList) },
                                onDeleteDownload = { onDeleteDownload(song) },
                                onPlayNext = { onPlayNext(song) },
                                onAddToPlaylist = { onAddToPlaylist(song) },
                                onRemove = { onDeleteDownload(song) }
                            )
                        }
                    }
                } else {
                    // Empty State for Downloads
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CloudDone,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "暂无离线下载歌曲",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "在播放页或歌曲菜单中点击【下载到本地】，即可随时随地脱网畅听！",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 20.sp
                                )
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = onNavigateToDiscover) {
                                Text("去搜搜好歌")
                            }
                        }
                    }
                }
            }

            2 -> {
                // ==================== TAB 2: 播放历史 ====================
                if (playHistoryList.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "最近播放（共 ${playHistoryList.size} 首）",
                            style = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )

                        TextButton(onClick = onClearHistory) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("清空历史", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 90.dp)
                    ) {
                        items(playHistoryList, key = { "history_${it.id}" }) { song ->
                            val isCurrent = playerState.currentSong?.id == song.id
                            val isBuffering = isCurrent && playerState.isBuffering
                            val hasError = isCurrent && playerState.playerError != null
                            val isFavorited = favoritesList.any { it.id == song.id }
                            val isDownloaded = downloadsList.any { it.id == song.id } || (song.localAudioPath != null && java.io.File(song.localAudioPath).exists())
                            val isDownloading = downloadStatusMap[song.id] is DownloadState.Downloading

                            SongListItem(
                                song = song,
                                isPlaying = isCurrent,
                                isFavorited = isFavorited,
                                isBuffering = isBuffering,
                                hasError = hasError,
                                isDownloaded = isDownloaded,
                                isDownloading = isDownloading,
                                onClick = { onSongClick(song, playHistoryList) },
                                onDownload = { onDownload(song) },
                                onDeleteDownload = { onDeleteDownload(song) },
                                onPlayNext = { onPlayNext(song) },
                                onAddToPlaylist = { onAddToPlaylist(song) },
                                onRemove = { onRemoveHistoryItem(song.id) }
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(54.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "暂无播放历史",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "播放过的歌曲会自动记录在这里，方便你快速重温",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        }

        // Discreet diagnosis log entry at bottom
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 72.dp, top = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = onOpenDebug) {
                Text(
                    text = "v1.2.0 · 诊断日志",
                    fontSize = 11.sp,
                    color = SlateMuted.copy(alpha = 0.6f)
                )
            }
        }
    }
}
}
