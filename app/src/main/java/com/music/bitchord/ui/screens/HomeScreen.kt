package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.artworkAt

/**
 * The YouTube Music front page: one LazyColumn of shelves, pull-to-refresh.
 * Tap on a playable card queues it straight into the player — the fork has no
 * detail pages, so browse-only cards (albums/playlists) render inert.
 */
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
        when (home) {
            is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
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
        feed.shelves.forEach { shelf ->
            item(key = "shelf:${shelf.title}:${shelf.items.size}") {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text(
                        shelf.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    when (shelf.layout) {
                        ShelfLayout.CARDS -> CardShelf(shelf, onPlaySong)
                        ShelfLayout.LIST -> ListShelf(shelf, onPlaySong)
                        ShelfLayout.VIDEO -> CardShelf(shelf, onPlaySong)
                    }
                }
            }
        }
        item {
            LaunchedEffect(feed.shelves.size) { onLoadMore() }
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
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

/** Square cover cards in one horizontal row — albums, singles, videos. */
@Composable
private fun CardShelf(shelf: HomeShelf, onPlaySong: (Song) -> Unit) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(shelf.items, key = { it.videoId ?: it.browseId ?: it.title }) { item ->
            ShelfCard(item, onPlaySong)
        }
    }
}

@Composable
private fun ShelfCard(item: ShelfItem, onPlaySong: (Song) -> Unit) {
    val song = item.asSong()
    Column(
        Modifier
            .width(if (item.isVideo) 220.dp else 140.dp)
            .clickable(enabled = song != null) { song?.let(onPlaySong) },
    ) {
        AsyncImage(
            model = item.thumbnailUrl?.artworkAt(if (item.isVideo) 544 else 288),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (item.isVideo) 16f / 9f else 1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Track rows — Quick picks, Listen again: vertical list of tappable songs. */
@Composable
private fun ListShelf(shelf: HomeShelf, onPlaySong: (Song) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        shelf.items.take(8).forEach { item ->
            val song = item.asSong() ?: return@forEach
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlaySong(song) }
                    .padding(vertical = 6.dp),
            ) {
                AsyncImage(
                    model = song.thumbnailUrl?.artworkAt(88),
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1C1C1E)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    song.durationText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * A shelf card is only playable when it names a track. Album/playlist cards
 * stay inert — the fork has no detail pages to open them with.
 */
private fun ShelfItem.asSong(): Song? = videoId?.let {
    Song(videoId = it, title = title, artist = subtitle, thumbnailUrl = thumbnailUrl)
}
