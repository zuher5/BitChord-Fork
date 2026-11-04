# DESIGN.md — Arsitektur Teknis Fork Minimalis BitChord

Dokumen ini menjelaskan **bagaimana** scope di `PRD.md` diimplementasikan,
berdasarkan inspeksi langsung source code upstream `kushagrasinghx/BitChord`.

## 1. Prinsip

Fork ini **tidak** menulis ulang mesin inti dari nol. Tiga bagian upstream
yang sudah teruji dan kompleks (klien Innertube, scanner MediaStore, player
Media3) dipertahankan apa adanya; yang diubah adalah **lapisan di atasnya**
(UI, navigasi, dependency graph).

Tetap pakai pola 3-modul Gradle:

| Modul | Isi | Target |
|---|---|---|
| `:shared` | Model data + `YtMusicRepository` (trimmed) | Android |
| `:sharedUi` | Design tokens, komponen kartu dasar | Android |
| `:app` | UI, navigasi, playback service | Android only |

Modul `tv/`, `wearos/`, `web/`, `backend/` dihapus dari `settings.gradle.kts`.

## 2. Pemetaan fitur → kode upstream

### 2.1 Home
- Sumber: `MainViewModel.Feed.HOME` (enum upstream: `{HOME, EXPLORE, LIBRARY}`,
  fork hanya pakai `HOME`).
- Data: `YtMusicRepository.home()`, `.moreHome()`, `.quickPicks()`.
- Dipangkas: `Feed.EXPLORE` (mood/genre grid), canvas art animasi di kartu.

### 2.2 Search
- Data: `YtMusicRepository.search()`, `.searchTypeahead()`,
  `.searchSuggestions()`, `.searchContinuation()`.
- Hasil: `SearchResult` sealed class (`Track`, `TopTrack`, `Browse`).
- Engine di baliknya: InnerTubeX (client Innertube YouTube Music) + NewPipeExtractor
  sebagai fallback resolusi stream — **dipertahankan apa adanya**, jangan
  disentuh kecuali ada bug.

### 2.3 Local
- Data: `LocalMediaRepository.getLocalMusic()` — query `MediaStore.Audio.Media`,
  filter ekstensi (`mp3/m4a/flac/ogg/opus/aac/webm/dsf/dff`), exclude
  ringtone/alarm/podcast/voice-note via `nonMusicPathSegments`.
- Grouping: `LocalGroups` (artist/album/folder dari metadata).
- UI acuan: `LocalFolder.kt` upstream, tab `LocalFolderTab.BASIC`
  (`SONGS, ARTISTS, ALBUMS`) — **skip** `PLAYLISTS`/`FOLDERS` dari
  `LocalFolderTab.DEVICE`.
- **Dipotong dari upstream**: integrasi ke `Downloads`/`DownloadStore` (lagu
  offline hasil download YouTube). Konsekuensinya, `Song` di fork ini hanya
  punya dua asal — *remote* (stream Innertube) dan *local* (URI MediaStore) —
  tanpa status "downloaded".

### 2.4 Player
- Media3 `ExoPlayer` + `MediaSessionService` (`PlaybackService.kt`,
  disederhanakan drastis dari 468K baris upstream ke inti: play/pause/seek/
  next/prev/shuffle/repeat + notifikasi media style).
- Facade ke Compose: `PlayerConnection.kt` (wrapper `MediaController`,
  expose `StateFlow<PlaybackState>`).
- UI: `MiniPlayer.kt` (dipertahankan hampir utuh dari upstream) + satu layar
  baru `NowPlayingScreen.kt`.
- **Dibuang dari `playback/`**: `CrossfadeController`, `EqualizerProcessor`,
  `LoudnessProcessor`, `SpatialAudioProcessor`, `AudioOutputPolicy`/
  `AudioRouting` (bit-perfect DAC), `playback/dsd/`, `playback/loopmix/`,
  `playback/smart/` (automix), `playback/cast/`, `PartySync.kt`.

## 3. Navigasi

Upstream pakai satu back-stack kustom (`AppNavigator` di
`ui/navigation/AppNavigator.kt`) yang meng-interleave `AppRoute` (sealed
interface — Settings, Equalizer, Downloads, Spotify, dst.) dengan "detail
stack" terpisah untuk halaman album/artist/playlist. Dirancang untuk puluhan
rute — berlebihan untuk 4 permukaan.

**Navigasi fork:**

```
AppTab { HOME, LOCAL, SEARCH }        // 3 tab bawah
   │
   ├─ state `selectedTab` + `AnimatedContent` (bukan androidx.navigation
   │   untuk tab utama — upstream sendiri juga tidak pakai itu untuk tab)
   │
   └─ "Now Playing" dibuka sebagai ModalBottomSheet penuh layar di atas
      tab manapun, dipicu dari tap MiniPlayer
```

Satu enum (`AppTab`) + `rememberSaveable` index. Tidak ada `RouteEntry`/
`detailDepth` interleaving — fork ini tidak punya Settings bertingkat,
Equalizer presets, dsb. yang jadi alasan `AppNavigator` dibuat kompleks di
upstream.

## 4. Struktur folder

```
app/src/main/java/com/music/bitchord/
├── MainActivity.kt            # shell tipis: setContent + 3 tab + MiniPlayer overlay
├── data/
│   ├── model/                 # Song, SearchResult, HomeFeed, HomeShelf (dari :shared, trimmed)
│   ├── LocalMediaRepository.kt   # scan MediaStore saja (tanpa bagian Downloads)
│   └── LocalGroups.kt            # grouping artist/album
├── playback/
│   ├── PlaybackService.kt     # MediaSessionService minimal
│   └── PlayerConnection.kt    # facade MediaController untuk Compose
└── ui/
    ├── screens/
    │   ├── HomeScreen.kt
    │   ├── LocalScreen.kt     # versi ramping dari LocalFolder.kt
    │   ├── SearchScreen.kt
    │   └── NowPlayingScreen.kt
    └── components/
        ├── MiniPlayer.kt      # dipertahankan hampir utuh
        ├── TabBar.kt          # versi sederhana dari FloatingBottomBar/GlassNavBar
        └── music/Lockups.kt   # SquareLockup/CircleLockup dari sharedUi
```

## 5. Dependency

**Dipertahankan** (`app/build.gradle.kts`):

```kotlin
// Compose + lifecycle inti
androidx.compose.* (bom, foundation, material3, ui)
androidx.activity:activity-compose
androidx.lifecycle:lifecycle-viewmodel-compose

// Player
androidx.media3:media3-exoplayer
androidx.media3:media3-session
androidx.media3:media3-common
androidx.media3:media3-datasource-okhttp

// Network ke Innertube (Home & Search)
io.ktor:ktor-client-*, kotlinx-serialization-json
com.github.MetrolistGroup.innertubex:innertubex-android

// Gambar
io.coil-kt.coil3:coil-compose
```

**Dibuang:** `play-services-cast-framework`, `mediarouter` (cast),
`play-services-wearable`/`wear-remote-interactions` (wear bridge), `smbj`
(SMB), `onnxruntime-android`, `libadb-android`, `conscrypt`/`bouncycastle`,
`compose-richtext` (lyrics markdown), `zxing` (QR Listen Together).
NewPipeExtractor opsional dipertahankan hanya sebagai fallback resolver.

## 6. Alur data per layar

- **Home:** `ViewModel.loadHome()` → `YtMusicRepository.home()` →
  `UiState<HomeFeed>` → `LazyColumn` shelves → tap kartu → `resolveAudio(song)`
  → `PlayerConnection.play()`.
- **Search:** `TextField` debounce → `searchTypeahead(input)` → submit →
  `search(query, filter)` → `LazyColumn<SearchResult>` → tap → `resolveAudio`
  → `play`.
- **Local:** cek permission → `LocalMediaRepository.getLocalMusic(context)`
  (cache in-memory, invalidasi via tombol "Pindai ulang") → tab Songs/
  Artists/Albums → tap lagu → `MediaItem.fromUri(song.localUri)` langsung ke
  `PlayerConnection.play()` (tanpa `resolveAudio` — sudah file lokal).
- **Player:** `PlayerConnection` membungkus `MediaController`, expose
  `StateFlow<PlaybackState>` (isPlaying, position, currentSong, queue).
  `MiniPlayer` subscribe ke flow ini, selalu tampil di atas tab bar saat ada
  queue aktif; tap → `NowPlayingScreen` (bottom sheet penuh layar).

## 7. Desain visual

Referensi: Design canvas artifact "BitChord Fork — Home, Local, Search,
Player" (4 artboard 390×844).

- **Warna:** bg `#0B0B0C`, surface `#1C1C1E`, teks utama `#F5F5F7`, teks
  sekunder `#9A9A9E`, aksen `#FF453A` (merah-koral).
- **Tipografi:** Inter (400/600/700/800).
- **Komponen berulang:** tab bar bawah (3 item: Home/Local/Search), mini
  player mengambang (muncul di atas tab bar, 56px tinggi), sheet Now Playing
  (artwork 280×280, progress bar, kontrol transport, shuffle/repeat).
- **Local:** pill tab (Songs/Artists/Albums) + list row (thumbnail 44×44,
  judul/artis, durasi).
- **Search:** search bar dengan ikon kaca pembesar, hasil dikelompokkan per
  section ("Hasil teratas", "Lagu", "Artis").

## 8. Langkah migrasi

1. Fork repo, hapus `tv/`, `wearos/`, `web/`, `backend/` dari
   `settings.gradle.kts` dan filesystem.
2. Di `:shared`, hapus `data/listentogether/`; trim `YtMusicRepository.kt` ke
   fungsi di §2.1–2.2.
3. Di `:app`, hapus: `data/discord`, `data/spotify`, `data/scrobbling`,
   `data/smb`, `data/webdav`, `data/listentogether`, `data/canvas`,
   `download/` (seluruh), `wear/`, `widget/`.
4. Di `playback/`, sisakan `PlaybackService.kt`, `PlayerConnection.kt`,
   `QueueCoordinator.kt`, `QueueShuffle.kt`, `Autoplay.kt`; strip isi
   `PlaybackService.kt` dari pemanggilan processor satu per satu sambil
   build untuk menemukan dependency yang putus.
5. Tulis ulang `MainActivity.kt`'s `BitChordApp()` dari nol mengikuti §3–4 —
   jangan coba trim versi 328K upstream. Rujuk `FloatingBottomBar.kt`/
   `GlassNavBar.kt` upstream hanya sebagai referensi visual.
6. Trim `ui/screens/LocalFolder.kt` jadi `LocalScreen.kt`: pertahankan
   `LocalFolderTab.BASIC`, `rememberLocalFolderGroups`; buang handler
   `onDelete`/`onUpload`/`onDownload`/`onRemoveFromCache`/playlist device.
7. Trim `data/settings/AppSettings.kt` (112K upstream) ke beberapa flag yang
   masih relevan (mis. `filterNonMusicAudio`).
8. Build incremental per modul (`:shared` → `:sharedUi` → `:app`); biarkan
   compiler Kotlin menunjukkan broken reference sebagai checklist pemangkasan.

## 9. Hal yang sengaja tidak diubah dari upstream

- `YtMusicRepository` — logic Innertube/cipher/typeahead rumit, sering
  di-update upstream mengikuti perubahan YouTube.
- `LocalMediaRepository.getLocalMusic()` — minus bagian `Downloads.saved`.
- Pola `MediaSessionService` + `MediaController` — sesuai Media3 best
  practice upstream, hanya dikosongkan dari DSP chain tambahan.
