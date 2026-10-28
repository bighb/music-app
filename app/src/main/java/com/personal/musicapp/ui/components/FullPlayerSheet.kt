package com.personal.musicapp.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.personal.musicapp.player.PlaybackMode
import com.personal.musicapp.player.PlayerState
import com.personal.musicapp.ui.theme.InkBlack
import com.personal.musicapp.ui.theme.RosePrimary
import com.personal.musicapp.ui.theme.SlateDark
import com.personal.musicapp.ui.theme.SlateMuted
import com.personal.musicapp.ui.util.FormatUtils

@Composable
fun FullPlayerDialog(
    playerState: PlayerState,
    isFavorited: Boolean,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    onDismiss: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleMode: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit = {},
    onOpenQueue: () -> Unit = {}
) {
    val song = playerState.currentSong ?: return

    val infiniteTransition = rememberInfiniteTransition(label = "vinyl_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragPositionRatio by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "收起",
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "正在播放",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = song.source,
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDownload) {
                            if (isDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else if (isDownloaded || song.localAudioPath != null) {
                                Icon(
                                    imageVector = Icons.Outlined.CloudDone,
                                    contentDescription = "已离线",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "离线下载",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        IconButton(onClick = onToggleFavorite) {
                            Icon(
                                imageVector = if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "收藏",
                                tint = if (isFavorited) Color(0xFFFF2D55) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Vinyl Disc record presentation with adaptive sizing
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 180.dp, minHeight = 180.dp, maxWidth = 280.dp, maxHeight = 280.dp)
                        .size(260.dp)
                        .shadow(24.dp, CircleShape)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF2B2B2B), Color(0xFF151515), Color(0xFF050505))
                            )
                        )
                        .border(5.dp, Color(0xFF262626), CircleShape)
                        .rotate(if (playerState.isPlaying) rotation else 0f),
                    contentAlignment = Alignment.Center
                ) {
                    // Inner grooves
                    Box(
                        modifier = Modifier
                            .size(210.dp)
                            .border(1.dp, Color(0xFF333333), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(170.dp)
                            .border(1.dp, Color(0xFF333333), CircleShape)
                    )

                    // Album Center Artwork
                    Box(
                        modifier = Modifier
                            .size(145.dp)
                            .clip(CircleShape)
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
                                modifier = Modifier.size(54.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Center hole
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0A0A0A))
                            .border(2.dp, Color(0xFF4A4A4A), CircleShape)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Song Title & Artist info
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = InkBlack
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyLarge.copy(color = SlateDark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )

                    val currentError = playerState.playerError
                    if (currentError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = currentError,
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else if (playerState.isBuffering) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "正在缓冲解析中...",
                            style = MaterialTheme.typography.bodySmall.copy(color = RosePrimary),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Seek bar & Timestamps
                Column(modifier = Modifier.fillMaxWidth()) {
                    val currentPos = if (isDraggingSlider) {
                        (dragPositionRatio * playerState.durationMs).toLong()
                    } else {
                        playerState.currentPositionMs
                    }

                    val sliderValue = if (playerState.durationMs > 0) {
                        if (isDraggingSlider) dragPositionRatio
                        else (playerState.currentPositionMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    Slider(
                        value = sliderValue,
                        onValueChange = {
                            isDraggingSlider = true
                            dragPositionRatio = it
                        },
                        onValueChangeFinished = {
                            isDraggingSlider = false
                            if (playerState.durationMs > 0) {
                                onSeekTo((dragPositionRatio * playerState.durationMs).toLong())
                            }
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = RosePrimary,
                            activeTrackColor = RosePrimary,
                            inactiveTrackColor = Color(0xFFE5E7EB)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = FormatUtils.formatDuration(currentPos),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = SlateDark,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Text(
                            text = FormatUtils.formatDuration(playerState.durationMs),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = SlateMuted,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Playback Controls Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Playback mode toggle
                    IconButton(onClick = onToggleMode) {
                        val icon = when (playerState.playbackMode) {
                            PlaybackMode.REPEAT_ALL -> Icons.Default.Repeat
                            PlaybackMode.REPEAT_ONE -> Icons.Default.RepeatOne
                            PlaybackMode.SHUFFLE -> Icons.Default.Shuffle
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = "切换播放模式",
                            tint = SlateDark,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Previous song
                    IconButton(onClick = onSkipPrevious) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "上一首",
                            tint = InkBlack,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    // Flat Center Play/Pause button
                    FilledIconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier.size(72.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = RosePrimary,
                            contentColor = Color.White
                        )
                    ) {
                        if (playerState.isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = Color.White,
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playerState.isPlaying) "暂停" else "播放",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }

                    // Next song
                    IconButton(onClick = onSkipNext) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "下一首",
                            tint = InkBlack,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    // Playlist queue button
                    IconButton(onClick = onOpenQueue) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "播放队列",
                            tint = SlateDark,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}
