package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.playback.PlayerState

/**
 * The full-screen player. A plain overlay rather than ModalBottomSheet: the
 * sheet's semantics fight a layout this tall, and slide-up/down is all the
 * motion the mockup asks for. Everything routes through Media3's controller,
 * so it works identically over remote (YouTube) and local queues.
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
    // Shuffle lives in QueueShuffle (order-level shuffle), not the Media3 flag —
    // collect it so the toggle's tint follows the actual queue state.
    val shuffleEnabled by com.music.bitchord.playback.QueueShuffle.enabled.collectAsState()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
    ) {
        // Grab handle
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Rounded.ExpandMore, contentDescription = "Tutup", tint = MaterialTheme.colorScheme.onSurface)
        }

        if (song == null) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("Tidak ada lagu", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        Spacer(Modifier.height(8.dp))
        AsyncImage(
            model = song.thumbnailUrl?.artworkAt(560),
            contentDescription = song.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1C1C1E)),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            song.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            song.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))

        Slider(
            value = if (state.durationMs > 0) {
                (state.position.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            } else 0f,
            onValueChange = { fraction -> onSeekTo((fraction * state.durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(state.position.positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    Icons.Rounded.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPrevious, enabled = state.hasPrevious) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Sebelumnya", modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(36.dp))
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onTogglePlay) {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isPlaying) "Jeda" else "Putar",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onNext, enabled = state.hasNext) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Berikutnya", modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onCycleRepeat) {
                when (state.repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_ONE -> Icon(Icons.Rounded.RepeatOne, contentDescription = "Ulangi satu", tint = MaterialTheme.colorScheme.primary)
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> Icon(Icons.Rounded.Repeat, contentDescription = "Ulangi semua", tint = MaterialTheme.colorScheme.primary)
                    else -> Icon(Icons.Rounded.Repeat, contentDescription = "Ulangi", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
