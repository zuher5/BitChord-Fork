package com.music.bitchord.ui.components

import com.music.bitchord.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.playback.PlayerState
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics

/**
 * The card's horizontal margin, per the mockup — the host already pads the
 * bottom for the tab bar, so only the sides are set here.
 */
private val CARD_MARGIN_HORIZONTAL = 8.dp

private val CARD_HEIGHT = 56.dp

/**
 * The card's corner: 12dp, a rounded rectangle rather than the old pill —
 * the mockup's mini player is a floating card, not a capsule.
 */
private val CARD_SHAPE = RoundedCornerShape(12.dp)

private val ART_SIZE = 38.dp
private val ART_CORNER = 7.dp

/** The gap between artwork and text, per the mockup. */
private val ART_GAP = 10.dp

/** The play/pause glyph itself, inside the stock 48dp IconButton slot. */
private val GLYPH_SIZE = 22.dp

/** The spinner that stands in for the play glyph, kept in proportion to it. */
private val SPINNER_SIZE = 22.dp

/**
 * The slim card above the tab bar: artwork, title, play/pause. It is the
 * fork's whole transport surface outside the now playing screen, so it talks
 * to the [MediaController] directly rather than through callbacks the host
 * owns. The whole row opens the full player; the mockup has no swipe gestures
 * and no skip buttons here, so none are offered.
 */
@Composable
fun MiniPlayer(
    state: PlayerState,
    controller: MediaController?,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = state.song ?: return
    val haptics = rememberHaptics()
    Box(
        modifier = modifier
            .padding(horizontal = CARD_MARGIN_HORIZONTAL)
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            // Deliberately silent: the whole card is the target, so it catches
            // stray taps meant for the page behind it, and the player rising
            // is its own confirmation. The glyph on it still buzzes.
            .clickable(onClick = onOpenNowPlaying)
            // Black rather than the token shadows: a dark card's shadow only
            // reads if it is pure black, which is what the mockup draws.
            .shadow(
                elevation = 8.dp,
                shape = CARD_SHAPE,
                ambientColor = Color.Black,
                spotColor = Color.Black,
            )
            .clip(CARD_SHAPE)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(ART_SIZE)
                    .clip(RoundedCornerShape(ART_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = song.thumbnailUrl?.artworkAt(ROW_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(ART_GAP))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.isLoading) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onBackground,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(SPINNER_SIZE),
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        haptics.play(if (state.isPlaying) Haptic.Pause else Haptic.Resume)
                        if (state.isPlaying) controller?.pause() else controller?.play()
                    },
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(GLYPH_SIZE),
                    )
                }
            }
        }
    }
}
