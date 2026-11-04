package com.music.bitchord.ui.components

import com.music.bitchord.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.playback.PlayerState
import com.music.bitchord.sharedui.resources.Res as SharedRes
import com.music.bitchord.sharedui.resources.ic_player_next
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs

/**
 * The mini player's side margins: the same gutter the floating tab bar below
 * it keeps, so the two float as one stack.
 */
private val BAR_GUTTER = 16.dp

/**
 * The transport buttons' touch target. Material's default 48dp is what a bar
 * this slim is really made of, so it sets the height on its own.
 */
private val GLYPH_SLOT = 40.dp

/**
 * The play and skip glyphs themselves.
 *
 * Deliberately grown inside [GLYPH_SLOT] rather than by growing the slot: the
 * slot is level with the 40dp artwork opposite it, and it is the taller of the
 * two that sets the row's height — so a bigger slot would make the whole bar
 * taller, which is not what a bigger glyph is being asked for. At 32 there is
 * still 4dp of clearance to the slot's edge on every side.
 */
private val GLYPH_SIZE = 32.dp

/** The spinner that stands in for the play glyph, kept in proportion to it. */
private val SPINNER_SIZE = 22.dp

/**
 * The gap between the two transport controls.
 *
 * Material asks for at least 8dp between adjacent touch targets, and these had
 * none: two [GLYPH_SLOT] boxes sharing an edge, so the boundary between "pause"
 * and "skip" was a line with nothing either side of it. What space there looked
 * to be was only the margin each glyph keeps inside its own slot, and a thumb
 * lands on a target's edge far more often than it lands on a glyph's.
 */
private val TRANSPORT_GAP = 8.dp

/**
 * Vertical padding, which with the 40dp artwork sets the bar's height at 56dp
 * and so its pill radius at 28.
 */
private val ROW_PADDING_VERTICAL = 8.dp

/**
 * Horizontal padding, deliberately larger than the vertical.
 *
 * A pill's ends are semicircles, so the edge nearest the artwork is not the
 * one beside it but the one curving away above and below it. At the artwork's
 * top corner that edge has already come 8.4dp in from the left — level with
 * where square corners would have put the whole side. Padding the ends by the
 * vertical figure would leave the artwork touching the curve; 12 clears it
 * with room, and reads as centred rather than jammed into the round.
 */
private val ROW_PADDING_HORIZONTAL = 12.dp

/**
 * The artwork's corner, on the 8dp every other thumbnail in the app carries.
 */
private val ART_CORNER = 8.dp

/** Distance that makes a horizontal drag an intentional track change. */
private val TRACK_SWIPE_THRESHOLD = 72.dp

/**
 * The bar's sideways drags: a left swipe advances through the queue, a right
 * one goes back, matching the full player's artwork. The tap to open the now
 * playing screen is left to the [clickable][androidx.compose.foundation.clickable]
 * beside it, and is cancelled by this consuming the drag.
 *
 * One detector rather than one per button, so a gesture is decided once, by
 * the way it is heading as it passes the touch slop. Track swipes wait until
 * the finger lifts, so one long gesture never skips more than one item.
 */
@Composable
internal fun Modifier.miniPlayerGestures(
    onNext: () -> Unit,
    onPrevious: () -> Unit,
): Modifier {
    // Playback state updates can recompose the bar while a finger is down.
    // Keep the gesture coroutine alive through those updates while still
    // dispatching to the latest controller callbacks when the drag finishes.
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    return pointerInput(Unit) {
        val trackThreshold = TRACK_SWIPE_THRESHOLD.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pointer = down.id
            // Up to the slop: which way this is heading, or nothing at all.
            var start: PointerInputChange? = null
            while (start == null) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == pointer }
                    ?: return@awaitEachGesture
                // Lifted inside the slop is a tap, and the tap is the clickable's.
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                val moved = change.position - down.position
                if (moved.getDistance() < viewConfiguration.touchSlop) continue
                // A vertical drag has nothing to do on the bar — left as it
                // always was, a drag that ends inside the bar still counts as
                // its tap.
                if (abs(moved.x) < abs(moved.y)) return@awaitEachGesture
                change.consume()
                start = change
            }
            val slopped = start ?: return@awaitEachGesture
            var dragged = slopped.position.x - down.position.x
            // Read before consuming: a consumed change reports no movement.
            val finished = drag(pointer) { change ->
                dragged += change.positionChange().x
                change.consume()
            }
            if (!finished) return@awaitEachGesture
            when {
                dragged <= -trackThreshold -> currentOnNext()
                dragged >= trackThreshold -> currentOnPrevious()
            }
        }
    }
}

/**
 * The slim bar above the tab bar: what is playing, play/pause, skip. It is the
 * fork's whole transport surface outside the now playing screen, so it talks to
 * the [MediaController] directly rather than through callbacks the host owns.
 *
 * A solid surface rather than the frosted glass the upstream bar wore: the
 * blur's backdrop state belonged to the upstream player sheet, which the fork
 * does not have, and the floating tab bar under it is a plain Surface too.
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
    // percent rather than a dp figure, so the corner stays exactly half the
    // height if the row's contents ever change it — which is what keeps a pill
    // a pill instead of a rounded rectangle. Same idiom as the floating tab
    // bar directly below it, so the two shapes are the same family.
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .padding(horizontal = BAR_GUTTER)
            .miniPlayerGestures(
                onNext = {
                    haptics.play(Haptic.SkipNext)
                    controller?.seekToNextMediaItem()
                },
                onPrevious = {
                    haptics.play(Haptic.SkipPrevious)
                    controller?.seekToPreviousMediaItem()
                },
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), shape)
            // Deliberately silent: the whole bar is the target, so it catches
            // stray taps meant for the page behind it, and the sheet rising is
            // its own confirmation. The glyphs on it still buzz.
            .clickable(onClick = onOpenNowPlaying),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = ROW_PADDING_HORIZONTAL,
                    vertical = ROW_PADDING_VERTICAL,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(ART_CORNER))
                    .thumbnailBorder(RoundedCornerShape(ART_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = song.thumbnailUrl?.artworkAt(ROW_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // The badge in the artist line's colour and a long line faded
                // out at its end, as on the glass bar's mini player.
                ExplicitSongTitle(
                    song = song,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    badgeColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    fadeOverflow = true,
                )
                FadingLine(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.isLoading) {
                Box(Modifier.size(GLYPH_SLOT), contentAlignment = Alignment.Center) {
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
                    modifier = Modifier.size(GLYPH_SLOT),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(GLYPH_SIZE),
                    )
                }
            }
            Spacer(Modifier.width(TRANSPORT_GAP))
            IconButton(
                onClick = {
                    haptics.play(Haptic.SkipNext)
                    controller?.seekToNextMediaItem()
                },
                enabled = state.hasNext,
                modifier = Modifier.size(GLYPH_SLOT),
            ) {
                MiniPlayerNextGlyph(
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/**
 * The full player's skip-forward glyph, at the mini player's scale.
 *
 * Sized against the play glyph beside it the way the full player sizes it
 * against its own play button — about half as wide again and two thirds as
 * tall — and squashed by the same 0.85, so the two players' next buttons
 * read as one glyph at two sizes.
 */
@Composable
internal fun MiniPlayerNextGlyph(tint: Color) {
    Icon(
        painter = painterResource(SharedRes.drawable.ic_player_next),
        contentDescription = stringResource(R.string.widget_next),
        tint = tint,
        modifier = Modifier
            .size(NEXT_GLYPH_SIZE)
            .graphicsLayer { scaleY = 0.85f },
    )
}

private val NEXT_GLYPH_SIZE = 25.dp
