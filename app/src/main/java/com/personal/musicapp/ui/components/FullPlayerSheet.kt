package com.personal.musicapp.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lyrics
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.personal.musicapp.MusicApplication
import com.personal.musicapp.data.model.LyricEntry
import com.personal.musicapp.player.PlaybackMode
import com.personal.musicapp.player.PlayerState
import com.personal.musicapp.ui.theme.BorderLight
import com.personal.musicapp.ui.theme.InkBlack
import com.personal.musicapp.ui.theme.RosePrimary
import com.personal.musicapp.ui.theme.SlateDark
import com.personal.musicapp.ui.theme.SlateMuted
import com.personal.musicapp.ui.util.FormatUtils
import com.personal.musicapp.util.AppToast
import com.personal.musicapp.util.LrcParser
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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

    var showLyrics by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<List<LyricEntry>>(emptyList()) }
    var isLoadingLyrics by remember { mutableStateOf(true) }
    val lyricListState = rememberLazyListState()

    // 3D Flip Animation between Vinyl (0f) and Lyrics (180f)
    val flipRotationY by animateFloatAsState(
        targetValue = if (showLyrics) 180f else 0f,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "vinyl_lyrics_3d_flip"
    )

    // Pull-down to dismiss gesture state
    var rawVerticalDragY by remember { mutableFloatStateOf(0f) }
    val animatedOffsetY by animateFloatAsState(
        targetValue = rawVerticalDragY,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sheet_vertical_offset"
    )

    // Manual scroll cooldown for lyrics: pause auto-scroll for 3 seconds after user touches lyrics
    var isUserScrollingLyrics by remember { mutableStateOf(false) }
    LaunchedEffect(lyricListState.isScrollInProgress) {
        if (lyricListState.isScrollInProgress) {
            isUserScrollingLyrics = true
        } else if (isUserScrollingLyrics) {
            delay(3000)
            isUserScrollingLyrics = false
        }
    }

    LaunchedEffect(song.id) {
        isLoadingLyrics = true
        lyrics = MusicApplication.instance.lyricRepository.getLyrics(song)
        isLoadingLyrics = false
    }

    val currentLyricIndex = remember(lyrics, playerState.currentPositionMs) {
        LrcParser.findCurrentIndex(lyrics, playerState.currentPositionMs)
    }

    LaunchedEffect(currentLyricIndex, showLyrics, isUserScrollingLyrics) {
        if (showLyrics && !isUserScrollingLyrics && !lyricListState.isScrollInProgress && currentLyricIndex in lyrics.indices) {
            val target = (currentLyricIndex - 2).coerceAtLeast(0)
            lyricListState.animateScrollToItem(target)
        }
    }

    var totalDragX by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dismissProgress = (animatedOffsetY / 600f).coerceIn(0f, 1f)
        val sheetCornerRadius = (dismissProgress * 28f).dp

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, animatedOffsetY.roundToInt()) }
                .clip(RoundedCornerShape(topStart = sheetCornerRadius, topEnd = sheetCornerRadius))
                .pointerInput(showLyrics) {
                    detectVerticalDragGestures(
                        onDragStart = { rawVerticalDragY = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            if (dragAmount > 0 || rawVerticalDragY > 0f) {
                                change.consume()
                                rawVerticalDragY = (rawVerticalDragY + dragAmount).coerceAtLeast(0f)
                            }
                        },
                        onDragEnd = {
                            if (rawVerticalDragY > 180f) {
                                onDismiss()
                            }
                            rawVerticalDragY = 0f
                        },
                        onDragCancel = { rawVerticalDragY = 0f }
                    )
                },
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Pull Handle Pill + Action Bar
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp, bottom = 8.dp)
                            .width(38.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(SlateMuted.copy(alpha = 0.28f))
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "收起",
                                tint = InkBlack,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Vector Icon Pill Switcher (No Emojis, Outlined vs Filled semantics)
                            Row(
                                modifier = Modifier
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                                    .padding(3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(13.dp))
                                        .background(if (!showLyrics) MaterialTheme.colorScheme.surface else Color.Transparent)
                                        .clickable { showLyrics = false }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (!showLyrics) Icons.Filled.Album else Icons.Outlined.Album,
                                        contentDescription = null,
                                        tint = if (!showLyrics) RosePrimary else SlateMuted,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "唱片",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (!showLyrics) FontWeight.Bold else FontWeight.Medium,
                                            color = if (!showLyrics) RosePrimary else SlateMuted
                                        )
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(13.dp))
                                        .background(if (showLyrics) MaterialTheme.colorScheme.surface else Color.Transparent)
                                        .clickable { showLyrics = true }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (showLyrics) Icons.Filled.Lyrics else Icons.Outlined.Lyrics,
                                        contentDescription = null,
                                        tint = if (showLyrics) RosePrimary else SlateMuted,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "歌词",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (showLyrics) FontWeight.Bold else FontWeight.Medium,
                                            color = if (showLyrics) RosePrimary else SlateMuted
                                        )
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "${song.source} · 轻触唱片或下滑折叠",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = SlateMuted,
                                    fontSize = 10.sp
                                )
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val context = LocalContext.current
                            var lastDownloadClickTime by remember { mutableLongStateOf(0L) }

                            IconButton(
                                enabled = !isDownloading,
                                onClick = {
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastDownloadClickTime < 1000L) return@IconButton
                                    lastDownloadClickTime = now

                                    if (isDownloaded || song.localAudioPath != null) {
                                        AppToast.show(context, "《${song.title}》已离线保存")
                                        return@IconButton
                                    }
                                    onDownload()
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
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

                            IconButton(
                                onClick = onToggleFavorite,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = "收藏",
                                    tint = if (isFavorited) RosePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Center 3D Flippable Container: Vinyl Disc (front <= 90deg) OR Synced Lyrics (back > 90deg)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 8.dp)
                        .pointerInput(song.id) {
                            detectHorizontalDragGestures(
                                onDragStart = { totalDragX = 0f },
                                onHorizontalDrag = { _, dragAmount -> totalDragX += dragAmount },
                                onDragEnd = {
                                    if (totalDragX < -120f) {
                                        onSkipNext()
                                    } else if (totalDragX > 120f) {
                                        onSkipPrevious()
                                    }
                                }
                            )
                        }
                        .graphicsLayer {
                            rotationY = flipRotationY
                            cameraDistance = 14f * density
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (flipRotationY > 90f) {
                        // Back Face: Lyrics View (counter-rotated 180deg so text is not mirrored)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { rotationY = 180f },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoadingLyrics) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        color = RosePrimary,
                                        strokeWidth = 2.5.dp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "正在匹配歌词...",
                                        style = MaterialTheme.typography.bodySmall.copy(color = SlateMuted)
                                    )
                                }
                            } else if (lyrics.isEmpty()) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { showLyrics = false }
                                        .padding(16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Lyrics,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = SlateMuted.copy(alpha = 0.45f)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "暂无匹配歌词，轻触返回唱片",
                                        style = MaterialTheme.typography.bodyMedium.copy(color = SlateMuted)
                                    )
                                }
                            } else {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    LazyColumn(
                                        state = lyricListState,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(vertical = 56.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        itemsIndexed(lyrics) { index, entry ->
                                            val isActive = index == currentLyricIndex
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .clickable {
                                                        isUserScrollingLyrics = false
                                                        onSeekTo(entry.timeMs)
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = entry.text,
                                                    style = if (isActive) {
                                                        MaterialTheme.typography.titleMedium.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            color = RosePrimary,
                                                            fontSize = 18.sp
                                                        )
                                                    } else {
                                                        MaterialTheme.typography.bodyMedium.copy(
                                                            fontWeight = FontWeight.Normal,
                                                            color = SlateDark.copy(alpha = 0.48f),
                                                            fontSize = 15.sp
                                                        )
                                                    },
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    }

                                    // Floating subtle button at bottom-end to flip back to Vinyl
                                    Surface(
                                        onClick = { showLyrics = false },
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(8.dp)
                                            .height(28.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Album,
                                                contentDescription = "返回封面",
                                                tint = SlateDark,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "封面",
                                                fontSize = 11.sp,
                                                color = SlateDark,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Front Face: Vinyl Disc presentation (Tap to 3D flip to Lyrics)
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
                                .rotate(if (playerState.isPlaying) rotation else 0f)
                                .clickable { showLyrics = true },
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
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

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

                Spacer(modifier = Modifier.height(14.dp))

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
                            inactiveTrackColor = BorderLight
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

                Spacer(modifier = Modifier.height(14.dp))

                // Playback Controls Row: Visual Anchor Hierarchy
                // Level 3 (Outer): 24dp SlateMuted | Level 2 (Inner): 32dp InkBlack | Level 1 (Center Hero): 64dp RosePrimary + Soft Glow Shadow
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Level 3: Playback mode toggle
                    IconButton(
                        onClick = onToggleMode,
                        modifier = Modifier.size(48.dp)
                    ) {
                        val icon = when (playerState.playbackMode) {
                            PlaybackMode.REPEAT_ALL -> Icons.Default.Repeat
                            PlaybackMode.REPEAT_ONE -> Icons.Default.RepeatOne
                            PlaybackMode.SHUFFLE -> Icons.Default.Shuffle
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = "切换播放模式",
                            tint = SlateMuted,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Center Cluster: Previous + Hero Play/Pause + Next
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // Level 2: Previous song
                        IconButton(
                            onClick = onSkipPrevious,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "上一首",
                                tint = InkBlack,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // Level 1: Hero Play/Pause button (64dp + diffused elevation shadow)
                        FilledIconButton(
                            onClick = onTogglePlay,
                            modifier = Modifier
                                .size(64.dp)
                                .shadow(
                                    elevation = 14.dp,
                                    shape = CircleShape,
                                    ambientColor = RosePrimary,
                                    spotColor = RosePrimary
                                ),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = RosePrimary,
                                contentColor = Color.White
                            )
                        ) {
                            if (playerState.isBuffering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = Color.White,
                                    strokeWidth = 2.8.dp
                                )
                            } else {
                                Icon(
                                    imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playerState.isPlaying) "暂停" else "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }

                        // Level 2: Next song
                        IconButton(
                            onClick = onSkipNext,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "下一首",
                                tint = InkBlack,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    // Level 3: Playlist queue button
                    IconButton(
                        onClick = onOpenQueue,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "播放队列",
                            tint = SlateMuted,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
