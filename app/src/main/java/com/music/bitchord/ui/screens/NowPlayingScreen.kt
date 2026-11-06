package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.playback.PlayerState
import com.music.bitchord.playback.QueueShuffle

private val CAPTION_GREY = Color(0xFF8E8E93)
private val TEXT_SECONDARY = Color(0xFF9A9A9E)
private val TRACK_BG = Color(0xFF3A3A3C)

/**
 * The full-screen player designed exactly to the mockup spec (Player.html):
 * 280dp artwork with 16dp rounded corners and heavy shadow, 4dp progress bar
 * with remaining runtime as negative time, 62dp white play FAB, and bottom
 * info row showing the next track seed.
 */
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
    modifier: Modifier = Modifier,
) {
    val song = state.song
    val shuffleEnabled by QueueShuffle.enabled.collectAsState()
    var isLiked by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
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
            IconButton(onClick = { /* inert in mockup */ }) {
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
            IconButton(onClick = { isLiked = !isLiked }) {
                Icon(
                    imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Sukai lagu",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // Progress bar (4dp high, track #3A3A3C, active #FF453A)
        val duration = state.durationMs.coerceAtLeast(0L)
        val position = state.position.positionMs.coerceIn(0L, duration.coerceAtLeast(1L))
        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

        Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TRACK_BG)
                    .pointerInput(duration) {
                        detectTapGestures { offset ->
                            if (duration > 0) {
                                val targetFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                onSeekTo((targetFraction * duration).toLong())
                            }
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(position), fontSize = 11.sp, color = CAPTION_GREY)
                val remaining = (duration - position).coerceAtLeast(0L)
                Text("-${formatTime(remaining)}", fontSize = 11.sp, color = CAPTION_GREY)
            }
        }

        // Transport controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp, start = 6.dp, end = 6.dp),
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
                .padding(top = 26.dp),
        ) {
            IconButton(onClick = { /* inert in mockup */ }, modifier = Modifier.size(24.dp)) {
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
            IconButton(onClick = { /* inert in mockup */ }, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = "Antrean",
                    tint = CAPTION_GREY,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
