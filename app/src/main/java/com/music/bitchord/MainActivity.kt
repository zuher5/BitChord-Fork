package com.music.bitchord

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    val home by viewModel.home.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val results by viewModel.results.collectAsState()
    val filter by viewModel.filter.collectAsState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { fadeIn(slideInVertically { it / 8 }) togetherWith fadeOut(slideOutVertically { -it / 8 }) },
                label = "tabs",
                modifier = Modifier.weight(1f),
            ) { tab ->
                when (tab) {
                    AppTab.HOME -> HomeScreen(
                        home = home,
                        onRefresh = viewModel::loadHome,
                        onLoadMore = viewModel::loadMoreHome,
                        onPlaySong = { song -> scope.launch { controller.playSongs(listOf(song), 0) } },
                    )
                    AppTab.LOCAL -> LocalScreen(
                        onPlaySongs = { songs, index -> scope.launch { controller.playSongs(songs, index) } },
                    )
                    AppTab.SEARCH -> SearchScreen(
                        suggestions = suggestions,
                        results = results,
                        filter = filter,
                        onInput = viewModel::onSearchInput,
                        onSubmit = { query, filter -> viewModel.search(query, filter) },
                        onPlaySong = { song -> scope.launch { controller.playSongs(listOf(song), 0) } },
                    )
                }
            }

            // Mini player floats above the tab bar, as the mockup has it.
            if (playerState.song != null) {
                MiniPlayer(
                    state = playerState,
                    controller = controller,
                    onOpenNowPlaying = { nowPlayingOpen = true },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 72.dp),
                )
            }
            TabBar(
                selected = selectedTab,
                onSelect = { selectedTab = it },
            )
        }

        if (nowPlayingOpen && playerState.song != null) {
            NowPlayingOverlay(
                controller = controller,
                onDismiss = { nowPlayingOpen = false },
            )
        }
    }
}

@Composable
private fun NowPlayingOverlay(controller: MediaController?, onDismiss: () -> Unit) {
    val playerState = rememberPlayerState(controller)
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
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
        )
    }
}
