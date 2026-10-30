package com.personal.musicapp.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.personal.musicapp.data.local.DownloadState
import com.personal.musicapp.data.model.Song
import com.personal.musicapp.player.PlayerState
import com.personal.musicapp.ui.components.SongListItem
import com.personal.musicapp.ui.theme.InkBlack
import com.personal.musicapp.ui.theme.RosePrimary
import com.personal.musicapp.ui.theme.RoseTint
import com.personal.musicapp.ui.theme.SlateDark
import com.personal.musicapp.ui.theme.SlateMuted

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    searchResults: List<Song>,
    searchHistory: List<String>,
    onSelectHistoryQuery: (String) -> Unit,
    onDeleteHistoryQuery: (String) -> Unit,
    onClearSearchHistory: () -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    playerState: PlayerState,
    favoritesList: List<Song>,
    playHistoryList: List<Song> = emptyList(),
    downloadsCount: Int = 0,
    hasSearched: Boolean = false,
    onClearSearchResult: () -> Unit = {},
    onNavigateToLibrary: (Int) -> Unit = {},
    onResumePlayback: (Song) -> Unit = {},
    onTogglePlayPause: () -> Unit = {},
    onOpenFullPlayer: () -> Unit = {},
    downloadStatusMap: Map<String, DownloadState> = emptyMap(),
    onSongClick: (Song) -> Unit,
    onDownload: (Song) -> Unit = {},
    onDeleteDownload: (Song) -> Unit = {},
    onPlayNext: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onOpenDebug: () -> Unit,
    youtubeCookie: String? = null,
    onOpenYouTubeAuth: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    val infiniteTransition = rememberInfiniteTransition(label = "resume_card_rotation")
    val resumeRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 15000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "resumeRotation"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Top Header: App Title & YouTube Auth Status Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "发现音乐",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )

            Surface(
                onClick = onOpenYouTubeAuth,
                shape = RoundedCornerShape(20.dp),
                color = if (!youtubeCookie.isNullOrBlank()) Color(0xFF2E7D32).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.height(30.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(if (!youtubeCookie.isNullOrBlank()) Color(0xFF2E7D32) else Color(0xFFFF0000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (!youtubeCookie.isNullOrBlank()) "YouTube 会员" else "YouTube 登录",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (!youtubeCookie.isNullOrBlank()) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Search Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = {
                    Text("搜索歌名、歌手或粘贴链接...", fontSize = 13.sp)
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = "搜索", tint = SlateMuted)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            onSearchQueryChange("")
                            onClearSearchResult()
                        }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "清空", tint = SlateMuted)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    onSearch()
                }),
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    focusedIndicatorColor = RosePrimary,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()
                    onSearch()
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(52.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Text("搜索")
            }
        }

        // Search History Tags directly beneath search bar
        if (searchHistory.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = SlateMuted,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "搜索历史",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = SlateDark
                        )
                    )
                }

                TextButton(
                    onClick = onClearSearchHistory,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = SlateMuted
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("清空", fontSize = 11.sp, color = SlateMuted)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                searchHistory.forEach { item ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF3F4F6),
                        modifier = Modifier.clickable {
                            onSearchQueryChange(item)
                            focusManager.clearFocus()
                            onSelectHistoryQuery(item)
                        }
                    ) {
                        Text(
                            text = item,
                            fontSize = 12.sp,
                            color = SlateDark,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Content Area: Loading, Error, Results, Empty State, or Default Dashboard
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.contains("http")) "正在解析音频链接..." else "正在全网多源匹配可用音源...",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                }
            }

            errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onSearch) {
                            Text("重试")
                        }
                    }
                }
            }

            searchResults.isEmpty() && hasSearched -> {
                // Empty search result state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(54.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "未找到相关歌曲",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "建议尝试其他关键词、歌手全名，或直接粘贴外链",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                onSearchQueryChange("")
                                onClearSearchResult()
                            }
                        ) {
                            Text("清空重试")
                        }
                    }
                }
            }

            searchResults.isEmpty() -> {
                // Default Home: Quick Resume Card if present, or clean placeholder
                val resumeCandidate = playerState.currentSong ?: playHistoryList.firstOrNull()
                if (resumeCandidate != null) {
                    val isCurrentlyPlaying = playerState.isPlaying && playerState.currentSong?.id == resumeCandidate.id
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = RoseTint,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    if (playerState.currentSong?.id == resumeCandidate.id) {
                                        onOpenFullPlayer()
                                    } else {
                                        onResumePlayback(resumeCandidate)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black)
                                        .rotate(if (isCurrentlyPlaying) resumeRotation else 0f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (resumeCandidate.displayCover.isNotEmpty()) {
                                        AsyncImage(
                                            model = resumeCandidate.displayCover,
                                            contentDescription = resumeCandidate.title,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isCurrentlyPlaying) "正在播放" else if (playerState.currentSong?.id == resumeCandidate.id) "继续播放" else "最近播放 · 点击恢复",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = RosePrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = resumeCandidate.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = InkBlack
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = resumeCandidate.artist,
                                        style = MaterialTheme.typography.bodySmall.copy(color = SlateMuted),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                FilledIconButton(
                                    onClick = {
                                        if (playerState.currentSong?.id == resumeCandidate.id) {
                                            onTogglePlayPause()
                                        } else {
                                            onResumePlayback(resumeCandidate)
                                        }
                                    },
                                    modifier = Modifier.size(42.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = RosePrimary,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isCurrentlyPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isCurrentlyPlaying) "暂停" else "播放",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
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
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(54.dp),
                                tint = SlateMuted.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "全网多音源聚合搜索",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = InkBlack
                                )
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "输入歌名、歌手或粘贴分享链接即可畅听",
                                style = MaterialTheme.typography.bodySmall.copy(color = SlateMuted)
                            )
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 90.dp)
                ) {
                    items(searchResults, key = { it.id }) { song ->
                        val isCurrent = playerState.currentSong?.id == song.id
                        val isBuffering = isCurrent && playerState.isBuffering
                        val hasError = isCurrent && playerState.playerError != null
                        val isFavorited = favoritesList.any { it.id == song.id }

                        val isDownloaded = downloadStatusMap[song.id] is DownloadState.Downloaded || song.localAudioPath != null
                        val isDownloading = downloadStatusMap[song.id] is DownloadState.Downloading

                        SongListItem(
                            song = song,
                            isPlaying = isCurrent,
                            isFavorited = isFavorited,
                            isBuffering = isBuffering,
                            hasError = hasError,
                            isDownloaded = isDownloaded,
                            isDownloading = isDownloading,
                            onClick = { onSongClick(song) },
                            onDownload = { onDownload(song) },
                            onDeleteDownload = { onDeleteDownload(song) },
                            onPlayNext = { onPlayNext(song) },
                            onAddToPlaylist = { onAddToPlaylist(song) }
                        )
                    }
                }
            }
        }
    }
}
