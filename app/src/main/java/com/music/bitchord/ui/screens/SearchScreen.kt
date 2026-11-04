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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.artworkAt

/**
 * Search bar with typeahead on top, committed results below. Sections follow
 * YouTube's own ranking: the promoted Top track first, then rows in order.
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
    var submitted by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        TextField(
            value = query,
            onValueChange = {
                query = it
                onInput(it)
            },
            placeholder = { Text("Cari lagu atau artis", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                submitted = query
                onSubmit(query, filter)
            }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        FilterChips(
            selected = filter,
            onSelect = { f ->
                onSubmit(submitted.ifBlank { query }, f)
            },
        )

        if (submitted.isBlank() && query.isNotBlank() && suggestions.isNotEmpty()) {
            LazyColumn {
                items(suggestions, key = { it }) { suggestion ->
                    Text(
                        suggestion,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                query = suggestion
                                submitted = suggestion
                                onSubmit(suggestion, filter)
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        } else {
            ResultsList(
                results = results,
                onPlaySong = onPlaySong,
                onEndReached = { onSubmit(submitted, filter) },
            )
        }
    }
}

@Composable
private fun FilterChips(selected: SearchFilter, onSelect: (SearchFilter) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        listOf(SearchFilter.ALL, SearchFilter.SONGS, SearchFilter.ARTISTS, SearchFilter.ALBUMS).forEach { f ->
            FilterChip(
                selected = f == selected,
                onClick = { onSelect(f) },
                label = { Text(f.label) },
            )
        }
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
        is UiState.Success -> LazyColumn(Modifier.fillMaxSize()) {
            results.data.forEach { row ->
                when (row) {
                    is SearchResult.TopTrack -> item(key = "top:${row.song.videoId}") {
                        SectionHeader("Hasil teratas")
                        TrackRow(row.song, onPlaySong)
                    }
                    is SearchResult.Track -> item(key = "track:${row.song.videoId}") {
                        TrackRow(row.song, onPlaySong)
                    }
                    is SearchResult.Browse -> item(key = "browse:${row.item.browseId}") {
                        BrowseRow(row.item)
                    }
                }
            }
            item {
                LaunchedEffect(results.data.size) { onEndReached() }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun TrackRow(song: Song, onPlaySong: (Song) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlaySong(song) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AsyncImage(
            model = song.thumbnailUrl?.artworkAt(88),
            contentDescription = song.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
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
        )
    }
}

/** Artist/album rows render inert — the fork has no detail pages. */
@Composable
private fun BrowseRow(item: BrowseItem) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AsyncImage(
            model = item.thumbnailUrl?.artworkAt(88),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1C1C1E)),
        )
        Spacer(Modifier.width(12.dp))
        Column {
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
}
