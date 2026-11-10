package com.personal.musicapp.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.personal.musicapp.player.PlayerState
import com.personal.musicapp.ui.theme.BorderLight
import com.personal.musicapp.ui.theme.InkBlack
import com.personal.musicapp.ui.theme.RosePrimary
import com.personal.musicapp.ui.theme.SlateDark
import com.personal.musicapp.ui.theme.SlateMuted
import kotlin.math.roundToInt

@Composable
fun MiniPlayerBar(
    playerState: PlayerState,
    onBarClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit = {},
    onOpenQueue: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val song = playerState.currentSong ?: return
    val haptic = LocalHapticFeedback.current

    val infiniteTransition = rememberInfiniteTransition(label = "mini_disc_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val progress = if (playerState.durationMs > 0) {
        (playerState.currentPositionMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(
        targetValue = dragOffsetX,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "mini_bar_swipe_offset"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .shadow(10.dp, RoundedCornerShape(28.dp))
            .border(BorderStroke(1.dp, BorderLight), RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .pointerInput(song.id) {
                detectHorizontalDragGestures(
                    onDragStart = { dragOffsetX = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragOffsetX = (dragOffsetX + dragAmount * 0.45f).coerceIn(-120f, 120f)
                    },
                    onDragEnd = {
                        when {
                            dragOffsetX < -42f -> {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSkipNext()
                            }
                            dragOffsetX > 42f -> {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSkipPrevious()
                            }
                        }
                        dragOffsetX = 0f
                    },
                    onDragCancel = { dragOffsetX = 0f }
                )
            }
            .clickable(onClick = onBarClick),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Swipable Track Info Section (Disc + Title/Artist)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .offset { IntOffset(animatedOffsetX.roundToInt(), 0) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Vinyl disc
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF2D2D2D), Color(0xFF151515))
                            )
                        )
                        .border(1.dp, Color(0xFF3F3F46), CircleShape)
                        .rotate(if (playerState.isPlaying) rotation else 0f),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(29.dp)
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
                                tint = RosePrimary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Song Title & Artist / Status
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = InkBlack
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val currentError = playerState.playerError
                    if (currentError != null) {
                        Text(
                            text = currentError,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (playerState.isBuffering) {
                        Text(
                            text = "正在缓冲解析音源...",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = RosePrimary,
                                fontSize = 11.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = "${song.artist} · 左右轻滑切歌",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = SlateMuted,
                                fontSize = 11.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Play/Pause button with Circular progress ring (48dp touch area)
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                if (playerState.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(34.dp),
                        strokeWidth = 2.dp,
                        color = RosePrimary
                    )
                } else {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(34.dp),
                        strokeWidth = 2.dp,
                        color = RosePrimary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playerState.isPlaying) "暂停" else "播放",
                        tint = RosePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Next Track
            IconButton(
                onClick = onSkipNext,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "下一首",
                    tint = SlateDark,
                    modifier = Modifier.size(24.dp)
                )
            }

            if (onOpenQueue != null) {
                IconButton(
                    onClick = onOpenQueue,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "播放队列",
                        tint = SlateDark,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
