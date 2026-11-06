package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.artworkAt

/** The page's shared 20dp gutter. */
private val PAGE_GUTTER = 20.dp

/** The mockup's dim caption grey for section headers and placeholders. */
private val CAPTION_GREY = Color(0xFF8E8E93)

/**
 * Search: a 30sp title, one rounded field, committed results grouped under
 * uppercase captions. The mockup carries no filter chips and no typeahead
 * dropdown — the [suggestions] and [filter] parameters survive only because
 * the caller owns their state; the input still feeds the ViewModel's debounce
 * through [onInput], and every submit runs the unfiltered [SearchFilter.ALL]
 * query.
 */
@Composable
fun SearchScreen(
    suggestions: List<String>,
    results: UiState<List<SearchResult>>,
    filter: SearchFilter,
    onInput: (String) -> Unit,
    onSubmit: (String, SearchFilter) -> Unit,
    onPlaySong: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        Text(
            "Search",
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(
                start = PAGE_GUTTER,
                end = PAGE_GUTTER,
                top = 22.dp,
                bottom = 14.dp,
            ),
        )
        SearchField(
            query = query,
            onQueryChange = {
                query = it
                onInput(it)
            },
            onSubmit = { onSubmit(query, SearchFilter.ALL) },
        )
        ResultsList(
            results = results,
            onPlaySong = onPlaySong,
            onEndReached = { if (query.isNotBlank()) onSubmit(query, SearchFilter.ALL) },
        )
    }
}

/** The rounded 10dp field: a magnifier, a line of text, nothing else. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = CAPTION_GREY,
            modifier = Modifier
                .padding(start = 12.dp)
                .size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 9.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            "Cari lagu atau artis",
                            fontSize = 15.sp,
                            color = CAPTION_GREY,
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun ResultsList(
    results: UiState<List<SearchResult>>,
    onPlaySong: (Song) -> Unit,
    onEndReached: () -> Unit,
) {
    when (results) {
        is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(results.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is UiState.Success -> {
            // Grouped by kind rather than listed raw: the mockup hangs rows
            // under four fixed captions, whatever order YouTube returned.
            val rows = results.data
            val topTracks = rows.filterIsInstance<SearchResult.TopTrack>()
            val tracks = rows.filterIsInstance<SearchResult.Track>()
            val artists = rows
                .filterIsInstance<SearchResult.Browse>()
                .filter { it.item.type == BrowseType.ARTIST }
            val albums = rows
                .filterIsInstance<SearchResult.Browse>()
                .filter { it.item.type != BrowseType.ARTIST }

            LazyColumn(Modifier.fillMaxSize()) {
                if (topTracks.isNotEmpty()) {
                    item(key = "header:top") { SectionHeader("HASIL TERATAS", first = true) }
                    items(topTracks, key = { "top:${it.song.videoId}" }) {
                        TrackRow(song = it.song, promoted = true, onPlaySong = onPlaySong)
                    }
                }
                if (tracks.isNotEmpty()) {
                    item(key = "header:songs") { SectionHeader("LAGU") }
                    items(tracks, key = { "track:${it.song.videoId}" }) {
                        TrackRow(song = it.song, onPlaySong = onPlaySong)
                    }
                }
                if (artists.isNotEmpty()) {
                    item(key = "header:artists") { SectionHeader("ARTIS") }
                    items(artists, key = { "artist:${it.item.browseId}" }) {
                        BrowseRow(it.item, circular = true)
                    }
                }
                if (albums.isNotEmpty()) {
                    item(key = "header:albums") { SectionHeader("ALBUM") }
                    items(albums, key = { "album:${it.item.browseId}" }) {
                        BrowseRow(it.item, circular = false)
                    }
                }
                item {
                    LaunchedEffect(rows.size) { onEndReached() }
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
    }
}

/** An uppercase caption above one group of rows. */
@Composable
private fun SectionHeader(title: String, first: Boolean = false) {
    Text(
        title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = CAPTION_GREY,
        modifier = Modifier.padding(
            start = PAGE_GUTTER,
            end = PAGE_GUTTER,
            // The field above already carries the page's top spacing; the first
            // section sits straight under it.
            top = if (first) 8.dp else 16.dp,
            bottom = 8.dp,
        ),
    )
}

/**
 * A track row. The promoted top result wears a "· Lagu" suffix and a trailing
 * play button instead of a runtime, per the mockup.
 */
@Composable
private fun TrackRow(
    song: Song,
    onPlaySong: (Song) -> Unit,
    promoted: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlaySong(song) }
            .padding(horizontal = PAGE_GUTTER, vertical = 9.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = song.thumbnailUrl?.artworkAt(88),
                contentDescription = song.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (promoted) "${song.artist} · Lagu" else song.artist,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (promoted) {
            IconButton(onClick = { onPlaySong(song) }) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Putar",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp),
                )
            }
        } else {
            Text(
                song.durationText.orEmpty(),
                fontSize = 12.sp,
                color = CAPTION_GREY,
            )
        }
    }
}

/** Artist/album rows render inert — the fork has no detail pages. */
@Composable
private fun BrowseRow(item: BrowseItem, circular: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 9.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(if (circular) CircleShape else RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = item.thumbnailUrl?.artworkAt(88),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                fontSize = 14.sp,
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
}
