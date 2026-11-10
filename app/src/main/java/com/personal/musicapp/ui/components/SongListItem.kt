package com.personal.musicapp.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.personal.musicapp.data.model.Song
import com.personal.musicapp.ui.theme.BorderLight
import com.personal.musicapp.ui.theme.InkBlack
import com.personal.musicapp.ui.theme.RosePrimary
import com.personal.musicapp.ui.theme.RoseTint
import com.personal.musicapp.ui.theme.SlateDark
import com.personal.musicapp.ui.theme.SlateMuted
import com.personal.musicapp.ui.util.FormatUtils

@Composable
fun AnimatedEqualizerIcon(
    isActivelyPlaying: Boolean,
    color: Color = RosePrimary,
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    maxHeight: Dp = 16.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer_bars")
    val bar1Ratio by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val bar2Ratio by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 360, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val bar3Ratio by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 510, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    val h1 = if (isActivelyPlaying) bar1Ratio else 0.4f
    val h2 = if (isActivelyPlaying) bar2Ratio else 0.75f
    val h3 = if (isActivelyPlaying) bar3Ratio else 0.5f

    Row(
        modifier = modifier.height(maxHeight),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .width(barWidth)
                .fillMaxHeight(h1)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Box(
            modifier = Modifier
                .width(barWidth)
                .fillMaxHeight(h2)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Box(
            modifier = Modifier
                .width(barWidth)
                .fillMaxHeight(h3)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongListItem(
    song: Song,
    isPlaying: Boolean,
    isActivelyPlaying: Boolean = isPlaying,
    isFavorited: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    hasError: Boolean = false,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onDeleteDownload: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    var showActionSheet by remember { mutableStateOf(false) }

    val containerBg = when {
        isSelectionMode && isSelected -> RoseTint
        hasError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        else -> Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerBg)
            .clickable {
                if (isSelectionMode && onToggleSelect != null) {
                    onToggleSelect()
                } else {
                    onClick()
                }
            }
            .padding(start = if (isPlaying && !isSelectionMode) 2.dp else 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            IconButton(
                onClick = { onToggleSelect?.invoke() },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "已选中" else "未选中",
                    tint = if (isSelected) RosePrimary else SlateMuted,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        } else if (isPlaying) {
            // 3dp Side Accent Indicator Bar (replaces heavy pink border/card)
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(RosePrimary)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        // Cover Image with In-place Buffering / Animated Equalizer Overlay
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (song.displayCover.isNotEmpty()) {
                AsyncImage(
                    model = song.displayCover,
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.42f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        AnimatedEqualizerIcon(
                            isActivelyPlaying = isActivelyPlaying,
                            color = Color.White,
                            barWidth = 3.dp,
                            maxHeight = 16.dp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Artist
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            hasError -> MaterialTheme.colorScheme.error
                            isPlaying -> RosePrimary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (song.source.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isPlaying) RosePrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = song.source,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isPlaying) RosePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }

                if (isFavorited) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "已收藏",
                        tint = RosePrimary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                if (hasError) {
                    Text(
                        text = "暂无可用音源",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp
                        )
                    )
                } else if (isPlaying && isBuffering) {
                    Text(
                        text = "正在解析音源...",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = RosePrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                } else {
                    if (isDownloaded || song.localAudioPath != null) {
                        Icon(
                            imageVector = Icons.Outlined.CloudDone,
                            contentDescription = "已离线",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (isPlaying) RosePrimary.copy(alpha = 0.85f) else SlateMuted
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (song.durationMs > 0) {
                        Text(
                            text = " · " + FormatUtils.formatDuration(song.durationMs),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = SlateMuted.copy(alpha = 0.75f),
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }
        }

        // Right Action Icons (Ensure 48dp minimum touch target)
        if (!isSelectionMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isDownloading) {
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                IconButton(
                    onClick = { showActionSheet = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (showActionSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showActionSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                // Song Header in Bottom Sheet
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (song.displayCover.isNotEmpty()) {
                            AsyncImage(
                                model = song.displayCover,
                                contentDescription = song.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = InkBlack
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (song.source.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = RoseTint
                                ) {
                                    Text(
                                        text = song.source,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = RosePrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.bodySmall.copy(color = SlateMuted),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    color = BorderLight.copy(alpha = 0.6f)
                )

                if (onPlayNext != null) {
                    ActionSheetItem(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        title = "下一首播放",
                        subtitle = "插入到当前播放歌曲之后",
                        onClick = {
                            showActionSheet = false
                            onPlayNext()
                        }
                    )
                }

                if (onAddToPlaylist != null) {
                    ActionSheetItem(
                        icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                        title = "加入播放队列",
                        subtitle = "追加到当前播放队列末尾",
                        onClick = {
                            showActionSheet = false
                            onAddToPlaylist()
                        }
                    )
                }

                if (onToggleFavorite != null) {
                    ActionSheetItem(
                        icon = if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        iconTint = if (isFavorited) RosePrimary else SlateDark,
                        title = if (isFavorited) "取消收藏" else "添加到我的收藏",
                        onClick = {
                            showActionSheet = false
                            onToggleFavorite()
                        }
                    )
                }

                if (onDownload != null && !isDownloaded && song.localAudioPath == null) {
                    ActionSheetItem(
                        icon = Icons.Default.Download,
                        title = if (isDownloading) "正在下载到本地..." else "下载到本地离线",
                        subtitle = "缓存音频与封面，断网也能畅听",
                        enabled = !isDownloading,
                        onClick = {
                            showActionSheet = false
                            onDownload()
                        }
                    )
                }

                if (onDeleteDownload != null && (isDownloaded || song.localAudioPath != null)) {
                    ActionSheetItem(
                        icon = Icons.Default.DeleteOutline,
                        iconTint = MaterialTheme.colorScheme.error,
                        textColor = MaterialTheme.colorScheme.error,
                        title = "删除本地离线文件",
                        subtitle = "释放设备存储空间",
                        onClick = {
                            showActionSheet = false
                            onDeleteDownload()
                        }
                    )
                }

                if (onRemove != null) {
                    ActionSheetItem(
                        icon = Icons.Default.DeleteOutline,
                        iconTint = MaterialTheme.colorScheme.error,
                        textColor = MaterialTheme.colorScheme.error,
                        title = "从当前列表中移除",
                        onClick = {
                            showActionSheet = false
                            onRemove()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionSheetItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color = SlateDark,
    textColor: Color = InkBlack,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (textColor == MaterialTheme.colorScheme.error) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (enabled) iconTint else SlateMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) textColor else SlateMuted.copy(alpha = 0.5f),
                    fontSize = 15.sp
                )
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = SlateMuted,
                        fontSize = 11.sp
                    )
                )
            }
        }
    }
}
