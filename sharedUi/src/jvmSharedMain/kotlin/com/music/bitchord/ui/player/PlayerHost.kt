package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the shared player artwork takes from the application it is drawn in.
 *
 * What differs per platform is underneath the UI: where settings are kept and
 * how a looping clip is decoded. Each application answers those once, here, and
 * installs its answer with [PlayerPlatform.install] before anything that reads
 * it is first drawn.
 *
 * This is the slim fork form: the upstream host carried the whole player —
 * lyrics, queue, Listen Together party, cast, watch output, music video, the
 * audio-pipeline dialog — and every screen that consumed those members was
 * removed with them. What is left is what the kept artwork still draws: the
 * mesh backdrop, the shared canvases, and [CanvasArtworkPlayer] for the motion
 * covers the desktop Top Picks shelf shows.
 */
interface PlayerHost {
    val settings: PlayerSettingsSource

    /**
     * Artwork for [song]. The upstream host resolved covers embedded in remote
     * files (WebDAV, SMB) here; with those sources gone the platform answer is
     * just the thumbnail URL, but the seam stays so a platform can still
     * override it.
     */
    @Composable
    fun rememberRemoteArtworkUrl(song: Song?): String? = song?.thumbnailUrl

    /** The looping clip over the cover, decoded however this platform decodes video. */
    @Composable
    fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier)
}

/** Where the installed [PlayerHost] lives. */
object PlayerPlatform {
    @Volatile
    private var installed: PlayerHost? = null

    val host: PlayerHost
        get() = installed ?: error("PlayerPlatform.install() was never called")

    fun install(host: PlayerHost) {
        installed = host
    }
}

/** The settings the shared artwork reads, named as the phone's AppSettings has them. */
interface PlayerSettingsSource {
    /** The user's reduce-motion switch, honoured by every looping animation. */
    val reduceAnimation: StateFlow<Boolean>
    /** The dial that backs progressive blur down where it costs too much. */
    val reduceDynamicBlur: StateFlow<Boolean>
}

/** The artwork's name for the installed host's settings. */
internal val PlayerSettings: PlayerSettingsSource
    get() = PlayerPlatform.host.settings

/** How a clip fills the bounds supplied by its caller. */
enum class CanvasContentMode {
    CROP,
    FIT_PORTRAIT,
}

/** Everything [CanvasArtworkPlayer] is asked, handed to the platform's decoder as one value. */
class CanvasVideoSpec(
    val canvas: CanvasArtwork,
    val isPlaying: Boolean,
    val contentMode: CanvasContentMode,
    val alignPortraitTop: Boolean,
    val onAspectRatioChanged: (Float) -> Unit,
    val portraitRevealBounds: IntSize,
    val presentationAlpha: () -> Float,
    val onRenderedChanged: (Boolean) -> Unit,
    val onFrameCaptured: (ImageBitmap) -> Unit,
    val refreshFrameEveryMs: Long?,
    val frameCapturePx: Int,
    val onCoverChanged: (Float) -> Unit,
    val bottomFade: Float,
    val bottomFadeEndPx: Float?,
    val pausedForTransition: Boolean,
    val resumeDelayMs: Long,
    val startPositionMs: Long,
    val revealInstantly: Boolean,
    val captureStill: Boolean,
    val onStillCaptured: (ImageBitmap?, Long) -> Unit,
    val onResumed: () -> Unit,
)

/**
 * The looping video that plays over a track's cover art — the platform's
 * [PlayerHost.CanvasVideo] behind a default-filled surface.
 */
@Composable
fun CanvasArtworkPlayer(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    contentMode: CanvasContentMode = CanvasContentMode.CROP,
    alignPortraitTop: Boolean = false,
    onAspectRatioChanged: (Float) -> Unit = {},
    portraitRevealBounds: IntSize = IntSize.Zero,
    presentationAlpha: () -> Float = { 1f },
    onRenderedChanged: (Boolean) -> Unit = {},
    onFrameCaptured: (ImageBitmap) -> Unit = {},
    refreshFrameEveryMs: Long? = null,
    /** 0 for no frames at all, which saves a main-thread readback on each fade-in. */
    frameCapturePx: Int = CANVAS_FRAME_CAPTURE_PX,
    onCoverChanged: (Float) -> Unit = {},
    bottomFade: Float = 0f,
    bottomFadeEndPx: Float? = null,
    pausedForTransition: Boolean = false,
    /** How long the clip holds still after [pausedForTransition] lifts before it plays on. */
    resumeDelayMs: Long = 0L,
    /** Where in the clip to start — a still's position, so its first frame is that still. */
    startPositionMs: Long = 0L,
    /** Shows the first frame at once rather than fading it in: there is a still under it that it matches. */
    revealInstantly: Boolean = false,
    /**
     * Asks for the frame on screen, once each time this turns true, through
     * [onStillCaptured] with the position it was taken at — null if it could
     * not be read. Only while [pausedForTransition], so the frame holds still.
     */
    captureStill: Boolean = false,
    onStillCaptured: (ImageBitmap?, Long) -> Unit = { _, _ -> },
    /** The clip has moved on from the frame it was last captured or started at. */
    onResumed: () -> Unit = {},
) {
    PlayerPlatform.host.CanvasVideo(
        CanvasVideoSpec(
            canvas = canvas,
            isPlaying = isPlaying,
            contentMode = contentMode,
            alignPortraitTop = alignPortraitTop,
            onAspectRatioChanged = onAspectRatioChanged,
            portraitRevealBounds = portraitRevealBounds,
            presentationAlpha = presentationAlpha,
            onRenderedChanged = onRenderedChanged,
            onFrameCaptured = onFrameCaptured,
            refreshFrameEveryMs = refreshFrameEveryMs,
            frameCapturePx = frameCapturePx,
            onCoverChanged = onCoverChanged,
            bottomFade = bottomFade,
            bottomFadeEndPx = bottomFadeEndPx,
            pausedForTransition = pausedForTransition,
            resumeDelayMs = resumeDelayMs,
            startPositionMs = startPositionMs,
            revealInstantly = revealInstantly,
            captureStill = captureStill,
            onStillCaptured = onStillCaptured,
            onResumed = onResumed,
        ),
        modifier,
    )
}

/** The longest edge of a frame handed to [CanvasArtworkPlayer]'s onFrameCaptured. */
const val CANVAS_FRAME_CAPTURE_PX = 128
