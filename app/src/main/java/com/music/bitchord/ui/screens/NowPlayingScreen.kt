package com.music.bitchord.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.playback.PlayerState
import com.music.bitchord.playback.QueueShuffle
import com.music.bitchord.playback.SleepTimer

private val CAPTION_GREY = Color(0xFF8E8E93)
private val TEXT_SECONDARY = Color(0xFF9A9A9E)
private val TRACK_BG = Color(0xFF3A3A3C)

/**
 * The full-screen player designed to the mockup spec (Player.html):
 * 280dp artwork with 16dp rounded corners and heavy shadow, 4dp progress bar
 * with remaining runtime as negative time, 62dp white play FAB, and bottom
 * info row showing the next track seed.
 *
 * Enriched with:
 * - Seekbar scrubbing / dragging support
 * - Like state persistence via [LikeState]
 * - Queue sheet (antrean lagu)
 * - Sleep timer sheet (pengatur waktu tidur)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    state: PlayerState,
    onDismiss: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSeekToQueueIndex: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val song = state.song
    val shuffleEnabled by QueueShuffle.enabled.collectAsState()
    val likeOverrides by LikeState.overrides.collectAsState()
    val isLiked = song != null && likeOverrides[song.videoId] == LikeStatus.LIKE
    val context = LocalContext.current

    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        // Drag pill handle indicating the sheet is draggable
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3A3A3C)),
            )
        }

        // Header bar: close, queue title, more options
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Tutup",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                "DIPUTAR DARI ANTREAN",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = CAPTION_GREY,
            )
            IconButton(onClick = { showSleepTimerSheet = true }) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "Opsi",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (song == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Tidak ada lagu", color = TEXT_SECONDARY)
            }
            return@Column
        }

        // Large 280x280 artwork centered vertically
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .shadow(
                        elevation = 20.dp,
                        shape = RoundedCornerShape(16.dp),
                        ambientColor = Color.Black.copy(alpha = 0.5f),
                        spotColor = Color.Black.copy(alpha = 0.5f),
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = song.thumbnailUrl?.artworkAt(560),
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Title + Artist + Heart action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    song.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    song.artist,
                    fontSize = 14.sp,
                    color = TEXT_SECONDARY,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = {
                song.videoId.let { id ->
                    val next = if (isLiked) LikeStatus.INDIFFERENT else LikeStatus.LIKE
                    LikeState.set(id, next)
                }
            }) {
                Icon(
                    imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Sukai lagu",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // Progress bar (4dp high, track #3A3A3C, active #FF453A, with dragging support)
        val duration = state.durationMs.coerceAtLeast(0L)
        val position = state.position.positionMs.coerceIn(0L, duration.coerceAtLeast(1L))
        var isDragging by remember { mutableStateOf(false) }
        var dragPositionMs by remember { mutableLongStateOf(0L) }

        val activePosition = if (isDragging) dragPositionMs else position
        val fraction = if (duration > 0) (activePosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

        Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .pointerInput(duration) {
                        detectTapGestures { offset ->
                            if (duration > 0) {
                                val targetFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                onSeekTo((targetFraction * duration).toLong())
                            }
                        }
                    }
                    .pointerInput(duration) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                if (duration > 0) {
                                    isDragging = true
                                    val targetFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                    dragPositionMs = (targetFraction * duration).toLong()
                                }
                            },
                            onDragEnd = {
                                if (isDragging) {
                                    onSeekTo(dragPositionMs)
                                    isDragging = false
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                            },
                            onHorizontalDrag = { change, _ ->
                                if (duration > 0) {
                                    val targetFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                    dragPositionMs = (targetFraction * duration).toLong()
                                    change.consume()
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TRACK_BG),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(activePosition), fontSize = 11.sp, color = CAPTION_GREY)
                val remaining = (duration - activePosition).coerceAtLeast(0L)
                Text("-${formatTime(remaining)}", fontSize = 11.sp, color = CAPTION_GREY)
            }
        }

        // Transport controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, start = 6.dp, end = 6.dp),
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    Icons.Rounded.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else CAPTION_GREY,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onPrevious, enabled = state.hasPrevious) {
                Icon(
                    Icons.Rounded.SkipPrevious,
                    contentDescription = "Sebelumnya",
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            // 62dp circular play FAB in white
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(62.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF5F5F7)),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        color = Color(0xFF0B0B0C),
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    IconButton(onClick = onTogglePlay, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isPlaying) "Jeda" else "Putar",
                            tint = Color(0xFF0B0B0C),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onNext, enabled = state.hasNext) {
                Icon(
                    Icons.Rounded.SkipNext,
                    contentDescription = "Berikutnya",
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            IconButton(onClick = onCycleRepeat) {
                val repeatActive = state.repeatMode != Player.REPEAT_MODE_OFF
                Icon(
                    imageVector = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = "Ulangi",
                    tint = if (repeatActive) MaterialTheme.colorScheme.primary else CAPTION_GREY,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // Bottom status row: speaker, next track title, queue icon
        val nextSong = state.queue.getOrNull(state.queueIndex + 1)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp),
        ) {
            IconButton(
                onClick = {
                    Toast.makeText(context, "Output audio aktif di perangkat", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.Rounded.Speaker,
                    contentDescription = "Output audio",
                    tint = CAPTION_GREY,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = if (nextSong != null) "Berikutnya: ${nextSong.title}" else "",
                fontSize = 12.sp,
                color = CAPTION_GREY,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            IconButton(
                onClick = { showQueueSheet = true },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = "Antrean",
                    tint = if (state.queue.isNotEmpty()) MaterialTheme.colorScheme.primary else CAPTION_GREY,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    // Modal Sheet: Antrean Lagu (Queue)
    if (showQueueSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQueueSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            QueueSheetContent(
                queue = state.queue,
                currentIndex = state.queueIndex,
                onSelectTrack = { index ->
                    onSeekToQueueIndex(index)
                    showQueueSheet = false
                },
            )
        }
    }

    // Modal Sheet: Pengatur Waktu Tidur (Sleep Timer)
    if (showSleepTimerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSleepTimerSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            SleepTimerSheetContent(
                onTimerSet = { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    showSleepTimerSheet = false
                },
            )
        }
    }
}

@Composable
private fun QueueSheetContent(
    queue: List<Song>,
    currentIndex: Int,
    onSelectTrack: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                "Antrean Lagu",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "${queue.size} lagu dalam antrean",
                fontSize = 12.sp,
                color = CAPTION_GREY,
            )
        }
        if (queue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Antrean kosong", color = CAPTION_GREY)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                itemsIndexed(queue, key = { index, song -> "q:${index}:${song.videoId}" }) { index, item ->
                    val isPlayingThis = index == currentIndex
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectTrack(index) }
                            .background(if (isPlayingThis) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "${index + 1}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isPlayingThis) MaterialTheme.colorScheme.primary else CAPTION_GREY,
                            modifier = Modifier.width(28.dp),
                        )
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            AsyncImage(
                                model = item.thumbnailUrl?.artworkAt(88),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.title,
                                fontSize = 14.sp,
                                fontWeight = if (isPlayingThis) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (isPlayingThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                item.artist,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isPlayingThis) {
                            Text(
                                "Memutar",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepTimerSheetContent(
    onTimerSet: (String) -> Unit,
) {
    val timerRunning = SleepTimer.isRunning
    val presets = SleepTimer.PRESETS

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            "Pengatur Waktu Tidur",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (timerRunning) {
            val remainingMs = SleepTimer.remainingMs()
            val statusText = if (SleepTimer.afterTrack.value) {
                "Timer aktif: berhenti setelah lagu saat ini selesai"
            } else if (remainingMs != null) {
                val mins = remainingMs / 60000L
                "Timer aktif: $mins menit lagi"
            } else {
                "Timer aktif"
            }
            Text(
                statusText,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Button(
                onClick = {
                    SleepTimer.cancel()
                    onTimerSet("Pengatur waktu tidur dimatikan")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                Text("Matikan Timer")
            }
        }

        presets.forEach { mins ->
            Text(
                "$mins Menit",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        SleepTimer.start(mins)
                        onTimerSet("Musik akan berhenti dalam $mins menit")
                    }
                    .padding(vertical = 12.dp),
            )
        }
        Text(
            "Setelah Lagu Saat Ini Selesai",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    SleepTimer.startAfterTrack()
                    onTimerSet("Musik akan berhenti setelah lagu ini selesai")
                }
                .padding(vertical = 12.dp),
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
