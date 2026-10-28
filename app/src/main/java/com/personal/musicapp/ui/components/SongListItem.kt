package com.personal.musicapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.personal.musicapp.data.model.Song
import com.personal.musicapp.ui.theme.InkBlack
import com.personal.musicapp.ui.theme.RosePrimary
import com.personal.musicapp.ui.theme.RoseTint
import com.personal.musicapp.ui.theme.SlateDark
import com.personal.musicapp.ui.theme.SlateMuted
import com.personal.musicapp.ui.util.FormatUtils

@Composable
fun SongListItem(
    song: Song,
    isPlaying: Boolean,
    isFavorited: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    hasError: Boolean = false,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    onDownload: (() -> Unit)? = null,
    onDeleteDownload: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }

    val containerBg = when {
        hasError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        isPlaying -> RoseTint
        else -> Color.Transparent
    }

    val activeBorder = if (isPlaying) {
        Modifier.border(BorderStroke(1.dp, RosePrimary.copy(alpha = 0.35f)), RoundedCornerShape(12.dp))
    } else Modifier

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerBg)
            .then(activeBorder)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cover Image
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (song.displayCover.isNotEmpty()) {
                AsyncImage(
                    model = song.displayCover,
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxWidth(),
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

        Spacer(modifier = Modifier.width(10.dp))

        // Title and Artist
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            hasError -> MaterialTheme.colorScheme.error
                            isPlaying -> InkBlack
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
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isPlaying) RosePrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = song.source,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                color = if (isPlaying) RosePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }

                if (isFavorited) {
                    Spacer(modifier = Modifier.width(4.dp))
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
                        text = "正在缓冲解析...",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = RosePrimary,
                            fontSize = 11.sp
                        )
                    )
                } else {
                    if (song.localAudioPath != null) {
                        Icon(
                            imageVector = Icons.Outlined.CloudDone,
                            contentDescription = "已离线",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }

                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (isPlaying) RosePrimary else SlateDark
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (song.durationMs > 0) {
                        Text(
                            text = " • " + FormatUtils.formatDuration(song.durationMs),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = SlateMuted,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }
        }

        // Action Icons
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isDownloading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .padding(2.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (isDownloaded || song.localAudioPath != null) {
                Icon(
                    imageVector = Icons.Outlined.CloudDone,
                    contentDescription = "已离线",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }

            // More Options Menu
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (onPlayNext != null) {
                        DropdownMenuItem(
                            text = { Text("下一首播放", fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showMenu = false
                                onPlayNext()
                            }
                        )
                    }

                    if (onAddToPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("加入播放队列", fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showMenu = false
                                onAddToPlaylist()
                            }
                        )
                    }

                    if (onDownload != null && !isDownloaded && song.localAudioPath == null) {
                        DropdownMenuItem(
                            text = { Text(if (isDownloading) "正在下载中..." else "下载到本地", fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            enabled = !isDownloading,
                            onClick = {
                                showMenu = false
                                onDownload()
                            }
                        )
                    }

                    if (onDeleteDownload != null && (isDownloaded || song.localAudioPath != null)) {
                        DropdownMenuItem(
                            text = { Text("删除离线文件", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showMenu = false
                                onDeleteDownload()
                            }
                        )
                    }

                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text("从列表中移除", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showMenu = false
                                onRemove()
                            }
                        )
                    }
                }
            }
        }
    }
}
