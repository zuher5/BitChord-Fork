package com.music.bitchord

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.session.MediaController
import com.music.bitchord.playback.playSongs
import com.music.bitchord.playback.rememberMediaController
import com.music.bitchord.playback.rememberPlayerState
import com.music.bitchord.playback.toggleShuffle
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.components.MiniPlayer
import com.music.bitchord.ui.components.TabBar
import com.music.bitchord.ui.screens.HomeScreen
import com.music.bitchord.ui.screens.LocalScreen
import com.music.bitchord.ui.screens.NowPlayingScreen
import com.music.bitchord.ui.screens.SearchScreen
import com.music.bitchord.ui.theme.BitChordTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The fork's whole navigation surface: three tabs plus one player sheet. */
enum class AppTab { HOME, LOCAL, SEARCH }

/**
 * The app shell: 3 tabs + a floating mini player + a full-screen Now Playing
 * overlay, per DESIGN.md §3–4. Written fresh for the fork rather than trimmed
 * from upstream's 332K version, which carried dozens of routes this fork
 * deliberately does not have.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BitChordTheme {
                BitChordApp()
            }
        }
    }
}

@Composable
fun BitChordApp(viewModel: MainViewModel = viewModel()) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }

    val controller = rememberMediaController()
    val playerState = rememberPlayerState(controller)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val home by viewModel.home.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val results by viewModel.results.collectAsState()
    val filter by viewModel.filter.collectAsState()

    // --- Back Handling ---
    // 1. If non-Home tab is active (and NowPlaying sheet is closed): switch to Home tab
    BackHandler(enabled = !nowPlayingOpen && selectedTab != AppTab.HOME) {
        selectedTab = AppTab.HOME
    }

    // 2. If Home tab is active (and NowPlaying sheet is closed): require double-back to exit
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = !nowPlayingOpen && selectedTab == AppTab.HOME) {
        val now = System.currentTimeMillis()
        if (now - lastBackPressTime < 2000L) {
            (context as? Activity)?.finish()
        } else {
            lastBackPressTime = now
            Toast.makeText(context, "Tekan sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 8 }) togetherWith
                        (fadeOut() + slideOutVertically { -it / 8 })
                },
                label = "tabs",
                modifier = Modifier.weight(1f),
            ) { tab ->
                when (tab) {
                    AppTab.HOME -> HomeScreen(
                        home = home,
                        onRefresh = viewModel::loadHome,
                        onLoadMore = viewModel::loadMoreHome,
                        onPlayShelfItem = { item ->
                            viewModel.playShelfItem(item) { songs, index ->
                                scope.launch { controller?.playSongs(songs, index) }
                            }
                        },
                    )
                    AppTab.LOCAL -> LocalScreen(
                        onPlaySongs = { songs, index -> scope.launch { controller?.playSongs(songs, index) } },
                    )
                    AppTab.SEARCH -> SearchScreen(
                        suggestions = suggestions,
                        results = results,
                        filter = filter,
                        onInput = viewModel::onSearchInput,
                        onSubmit = { query, filter -> viewModel.search(query, filter) },
                        onClearSearch = viewModel::clearSearch,
                        onPlaySong = { song -> scope.launch { controller?.playSongs(listOf(song), 0) } },
                        onPlayBrowseItem = { item ->
                            viewModel.playBrowseItem(item) { songs, index ->
                                scope.launch { controller?.playSongs(songs, index) }
                            }
                        },
                    )
                }
            }

            // Mini player floats above the tab bar, as the mockup has it.
            if (playerState.song != null) {
                MiniPlayer(
                    state = playerState,
                    controller = controller,
                    onOpenNowPlaying = { nowPlayingOpen = true },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
            TabBar(
                selected = selectedTab,
                onSelect = { selectedTab = it },
            )
        }

        NowPlayingOverlay(
            visible = nowPlayingOpen && playerState.song != null,
            controller = controller,
            onDismiss = { nowPlayingOpen = false },
        )
    }
}

@Composable
private fun NowPlayingOverlay(
    visible: Boolean,
    controller: MediaController?,
    onDismiss: () -> Unit,
) {
    val playerState = rememberPlayerState(controller)
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 140.dp.toPx() }

    // Intercept Back to close the NowPlaying sheet first
    BackHandler(enabled = visible) {
        onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                .draggable(
                    state = rememberDraggableState { delta ->
                        if (delta > 0 || dragOffsetY > 0) {
                            dragOffsetY = (dragOffsetY + delta).coerceAtLeast(0f)
                        }
                    },
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (dragOffsetY > dismissThreshold || velocity > 1000f) {
                            dragOffsetY = 0f
                            onDismiss()
                        } else {
                            scope.launch {
                                Animatable(dragOffsetY).animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow,
                                    ),
                                ) {
                                    dragOffsetY = value
                                }
                            }
                        }
                    },
                ),
        ) {
            NowPlayingScreen(
                state = playerState,
                onDismiss = onDismiss,
                onTogglePlay = { if (playerState.isPlaying) controller?.pause() else controller?.play() },
                onSeekTo = { ms -> controller?.seekTo(ms) },
                onNext = { controller?.seekToNextMediaItem() },
                onPrevious = { controller?.seekToPreviousMediaItem() },
                onToggleShuffle = { controller?.toggleShuffle() },
                onCycleRepeat = {
                    when (controller?.repeatMode) {
                        androidx.media3.common.Player.REPEAT_MODE_OFF ->
                            controller?.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                        androidx.media3.common.Player.REPEAT_MODE_ALL ->
                            controller?.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                        else -> controller?.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
                    }
                },
                onSeekToQueueIndex = { index -> controller?.seekToDefaultPosition(index) },
            )
        }
    }
}
