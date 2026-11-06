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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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

/** The page's shared 20dp gutter. */
private val PAGE_GUTTER = 20.dp

/** The mockup's dim caption grey — one step past onSurfaceVariant. */
private val CAPTION_GREY = Color(0xFF8E8E93)

/** Text on an unselected pill, a step brighter than onSurfaceVariant. */
private val PILL_OFF_TEXT = Color(0xFFC7C7CC)

/**
 * The device's own music: every track MediaStore holds, as Songs, Artists or
 * Albums. A 30sp title with a rescan button, pill tabs, and a count caption;
 * a tap anywhere queues straight into the player.
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
            "MusPlay butuh izin akses file musik untuk memutar lagu di perangkat ini.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Button(onClick = onRequest) {
            Text("Berikan izin", fontWeight = FontWeight.SemiBold)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = PAGE_GUTTER, end = 12.dp, top = 22.dp),
            ) {
                Text(
                    "Local",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRescan) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "Pindai ulang",
                        tint = CAPTION_GREY,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        item(key = "local-tabs") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER, vertical = 12.dp),
            ) {
                LocalTab.entries.forEach { entry ->
                    LocalPill(entry.label, selected = entry == tab, onClick = { tab = entry })
                }
            }
        }
        item(key = "local-count") {
            Text(
                "${songs.size} LAGU DI PERANGKAT",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = CAPTION_GREY,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 8.dp),
            )
        }

        when (tab) {
            LocalTab.SONGS -> itemsIndexed(songs, key = { _, song -> "song:${song.videoId}" }) { index, song ->
                SongRow(
                    song = song,
                    onClick = { onPlaySongs(songs, index) },
                )
            }
            LocalTab.ARTISTS -> items(artists, key = { it.browseId }) { group ->
                // An artist with no picture is a circle with a glyph, per the mockup.
                GroupRow(
                    title = group.title,
                    subtitle = songCount(group.songs.size),
                    art = null,
                    circular = true,
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
                    circular = false,
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
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else PILL_OFF_TEXT,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
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
            .padding(horizontal = PAGE_GUTTER, vertical = 9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(7.dp))
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
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            song.durationText.orEmpty(),
            fontSize = 12.sp,
            color = CAPTION_GREY,
        )
    }
}

/**
 * An artist or album row: 44dp art (or its glyph), a name and a count.
 * Artists wear the circular crop, albums the square one, per the mockup.
 */
@Composable
private fun GroupRow(
    title: String,
    subtitle: String,
    art: String?,
    circular: Boolean,
    placeholder: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER, vertical = 9.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(if (circular) CircleShape else RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
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
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                fontSize = 12.sp,
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
