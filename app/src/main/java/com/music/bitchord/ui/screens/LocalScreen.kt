package com.music.bitchord.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.LocalGroups
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.CoverPlaceholder

/** The Local screen's three readings of the device's tracks. */
private enum class LocalTab(val label: String) { SONGS("Songs"), ARTISTS("Artists"), ALBUMS("Albums") }

/**
 * The device's own music: every track MediaStore holds, as Songs, Artists or
 * Albums. The fork has no folder tree or offline playlists — one scan, three
 * groupings, and a tap anywhere queues straight into the player.
 *
 * Written fresh against the trimmed [LocalGroups] API rather than trimmed from
 * the upstream folder page, which carried the downloads, selection and upload
 * machinery this fork deliberately does not have.
 */
@Composable
fun LocalScreen(
    onPlaySongs: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var granted by remember { mutableStateOf(LocalMediaRepository.hasStoragePermission(context)) }
    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> granted = result.values.any { it } }

    // null until the first scan lands: the loading state. Kept across tab
    // switches and recompositions; a re-scan replaces it wholesale.
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var scanCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(granted, scanCount) {
        if (granted) songs = LocalMediaRepository.getLocalMusic(context)
    }

    val scanned = songs
    when {
        !granted -> PermissionPrompt(
            onRequest = { permissionRequest.launch(permissionsForDevice()) },
            modifier = modifier,
        )
        scanned == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        else -> LocalLibrary(
            songs = scanned,
            onRescan = { scanCount++ },
            onPlaySongs = onPlaySongs,
            modifier = modifier,
        )
    }
}

/** The audio permission this Android version asks for. */
private fun permissionsForDevice(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/** Centered ask, shown until the audio permission is granted. */
@Composable
private fun PermissionPrompt(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "BitChord needs access to your music files to show what's on this device.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Button(onClick = onRequest) {
            Text("Allow access", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LocalLibrary(
    songs: List<Song>,
    onRescan: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(LocalTab.SONGS) }

    // Grouped off the songs as listed, so a tap plays the rows the tab shows.
    // The page id prefix is unused here — the fork opens no group pages — but
    // LocalGroups builds ids either way, so a folder id of one word suffices.
    val artists = remember(songs) { LocalGroups.artistsOf("local", songs) }
    val albums = remember(songs) { LocalGroups.albumsOf("local", songs) }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "local-header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    LocalTab.entries.forEach { entry ->
                        LocalPill(entry.label, selected = entry == tab, onClick = { tab = entry })
                    }
                }
                TextButton(onClick = onRescan) {
                    Text(
                        "Pindai ulang",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        when (tab) {
            LocalTab.SONGS -> itemsIndexed(songs, key = { _, song -> "song:${song.videoId}" }) { index, song ->
                SongRow(
                    song = song,
                    onClick = { onPlaySongs(songs, index) },
                )
            }
            LocalTab.ARTISTS -> items(artists, key = { it.browseId }) { group ->
                GroupRow(
                    title = group.title,
                    subtitle = songCount(group.songs.size),
                    art = null,
                    placeholder = Icons.Rounded.Person,
                    onClick = { onPlaySongs(group.songs, 0) },
                )
            }
            LocalTab.ALBUMS -> items(albums, key = { it.browseId }) { group ->
                GroupRow(
                    title = group.title,
                    subtitle = listOfNotNull(
                        group.subtitle.takeIf { it.isNotBlank() },
                        songCount(group.songs.size),
                    ).joinToString(" · "),
                    art = group.thumbnailUrl,
                    placeholder = Icons.Rounded.Album,
                    onClick = { onPlaySongs(group.songs, 0) },
                )
            }
        }

        if (songs.isEmpty()) {
            item(key = "local-empty") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "No music found on this device",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One of the three pill tabs above the list. */
@Composable
private fun LocalPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** A track row: 44dp artwork, title, artist, runtime. */
@Composable
private fun SongRow(song: Song, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = song.thumbnailUrl?.artworkAt(ROW_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
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

/** An artist or album row: square art (or its glyph), a name and a count. */
@Composable
private fun GroupRow(
    title: String,
    subtitle: String,
    art: String?,
    placeholder: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))) {
            CoverPlaceholder(placeholder, Modifier.fillMaxSize())
            if (art != null) {
                AsyncImage(
                    model = art.artworkAt(ROW_ART_PX),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun songCount(count: Int): String =
    pluralStringResource(R.plurals.song_count_plural, count, count)
