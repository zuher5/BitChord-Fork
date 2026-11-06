package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.artworkAt

/** The page's shared 20dp gutter — headers and card rows alike. */
private val PAGE_GUTTER = 20.dp

/** Card geometry, per the mockup's fixed-width square covers. */
private val CARD_WIDTH = 128.dp
private val CARD_GAP = 14.dp
private val CARD_CORNER = 10.dp

/**
 * The YouTube Music front page: a 30sp title, then every shelf as one
 * horizontal row of fixed-width cards. Tap on a playable card queues it
 * straight into the player — the fork has no detail pages, so browse-only
 * cards (albums/playlists) render inert.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    home: UiState<HomeFeed>,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onPlaySong: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = home is UiState.Loading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        // The pull indicator is the loading state — PullToRefreshBox draws it
        // while it refreshes, so no centered spinner is layered on top.
        when (home) {
            is UiState.Loading -> Box(Modifier.fillMaxSize())
            is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    home.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onRefresh() },
                )
            }
            is UiState.Success -> ShelfList(
                feed = home.data,
                onPlaySong = onPlaySong,
                onLoadMore = onLoadMore,
            )
        }
    }
}

@Composable
private fun ShelfList(
    feed: HomeFeed,
    onPlaySong: (Song) -> Unit,
    onLoadMore: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "home-title") {
            Text(
                "Home",
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(
                    start = PAGE_GUTTER,
                    end = PAGE_GUTTER,
                    top = 22.dp,
                    bottom = 18.dp,
                ),
            )
        }
        feed.shelves.forEachIndexed { index, shelf ->
            item(key = "shelf:${shelf.title}:${shelf.items.size}") {
                // Every shelf renders as a card row — the mockup carries no
                // vertical track lists, whatever layout YouTube named.
                ShelfRow(shelf, first = index == 0, onPlaySong = onPlaySong)
            }
        }
        item {
            LaunchedEffect(feed.shelves.size) { onLoadMore() }
            Box(Modifier.fillMaxWidth().padding(PAGE_GUTTER), contentAlignment = Alignment.Center) {
                if (feed.continuation != null) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

/** One shelf: its header, then a horizontal row of [ShelfCard]s. */
@Composable
private fun ShelfRow(shelf: HomeShelf, first: Boolean, onPlaySong: (Song) -> Unit) {
    Column {
        Text(
            shelf.title,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(
                start = PAGE_GUTTER,
                end = PAGE_GUTTER,
                bottom = 10.dp,
                // The title header already carries the page's top spacing; the
                // first shelf sits straight under it.
                top = if (first) 0.dp else 22.dp,
            ),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            items(shelf.items, key = { it.videoId ?: it.browseId ?: it.title }) { item ->
                ShelfCard(item, onPlaySong)
            }
        }
    }
}

@Composable
private fun ShelfCard(item: ShelfItem, onPlaySong: (Song) -> Unit) {
    val song = item.asSong()
    Column(
        Modifier
            .width(CARD_WIDTH)
            .clickable(enabled = song != null) { song?.let(onPlaySong) },
    ) {
        Box(
            Modifier
                .size(CARD_WIDTH)
                .clip(RoundedCornerShape(CARD_CORNER))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = item.thumbnailUrl?.artworkAt(288),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.subtitle,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A shelf card is only playable when it names a track. Album/playlist cards
 * stay inert — the fork has no detail pages to open them with.
 */
private fun ShelfItem.asSong(): Song? = videoId?.let {
    Song(videoId = it, title = title, artist = subtitle, thumbnailUrl = thumbnailUrl)
}
