# PRD — BitChord Fork Minimalis

## 1. Latar belakang

[BitChord](https://github.com/kushagrasinghx/BitChord) adalah klien musik
YouTube Music multi-platform (Android, TV, Wear OS, desktop, web) dengan
fitur sangat luas: streaming + download offline, integrasi Spotify/Discord/
Last.fm, Listen Together (party sync lewat server), lyrics word-synced multi
provider, equalizer, bit-perfect USB DAC, automix DJ engine, dll.

Produk ini **bukan** itu. Ini adalah fork Android yang sengaja dipersempit
jadi pemutar musik harian yang ringan: dengar musik dari YouTube Music
(streaming) atau dari file lokal di HP, dengan pencarian dan player yang
solid — tanpa permukaan fitur yang bikin maintenance berat.

## 2. Tujuan

- Aplikasi musik Android yang **ringan secara kode dan dependency**, gampang
  dipahami satu orang, gampang di-maintain.
- Tetap memakai mesin inti BitChord yang sudah teruji (klien Innertube YouTube
  Music, Media3 player) — tidak menulis ulang dari nol.
- Scope fitur sengaja kecil: **Home, Local, Search, Player**. Tidak lebih.

## 3. Target pengguna

Orang yang ingin satu aplikasi untuk:
- Dengar musik dari YouTube Music tanpa iklan/UI YouTube yang berat, dan
- Dengar koleksi musik lokal (file MP3/FLAC dst. yang sudah ada di HP)

...dalam satu antarmuka yang sama, tanpa fitur sosial/integrasi akun lain yang
tidak mereka butuhkan.

## 4. Scope fitur (in)

### 4.1 Home
- Menampilkan rak ("Quick Picks", "Lanjutkan dengar", dst.) hasil `home()`
  dari Innertube API YouTube Music.
- Pull-to-refresh.
- Tap kartu → resolve audio → mulai putar.

### 4.2 Local
- Scan `MediaStore` untuk semua file audio di perangkat (mp3/m4a/flac/ogg/
  opus/aac/webm/dsf/dff), filter non-musik (ringtone/alarm/podcast/voice-note).
- 3 tab: **Songs**, **Artists**, **Albums** (pengelompokan otomatis dari
  metadata file).
- Tap lagu → putar langsung dari URI lokal (tanpa resolve jaringan).
- Minta izin `READ_MEDIA_AUDIO` (Android 13+) / `READ_EXTERNAL_STORAGE`.

### 4.3 Search
- Search bar dengan typeahead/saran.
- Submit → hasil pencarian dari Innertube (track, dan opsional artist/album
  sebagai section terpisah).
- Tap hasil → resolve audio → mulai putar.

### 4.4 Player
- Mini player mengambang (muncul di atas tab bar setiap ada lagu aktif),
  tampil di semua 3 tab.
- Now Playing sebagai sheet penuh layar: artwork besar, judul/artis, progress
  bar + waktu, kontrol play/pause/next/prev, shuffle, repeat.
- Playback latar belakang via foreground service (notifikasi media style
  standar Android).

## 5. Scope fitur (out) — sengaja tidak dibuat

| Fitur upstream | Alasan dihapus |
|---|---|
| Download/offline + tagging (FLAC/MP4/WebM) | Subsistem besar, tidak perlu untuk use-case streaming + file lokal yang sudah ada |
| Spotify, Discord RPC, scrobbling (Last.fm/ListenBrainz) | Integrasi akun pihak ketiga, bukan kebutuhan inti |
| Listen Together (party sync) | Butuh backend server terpisah (Go), kompleksitas tinggi untuk nilai tambah kecil di scope ini |
| Lyrics (multi-provider, word-synced, translasi) | Fitur nice-to-have, banyak dependency network tambahan |
| Equalizer, crossfade, loudness, spatial audio, bit-perfect USB DAC | DSP chain kompleks, niche untuk audiophile |
| SMB/WebDAV sebagai sumber folder | Sumber musik tambahan yang jarang dipakai kebanyakan user |
| Chromecast | Butuh Play Services tambahan |
| Automix/loopmix (DJ engine beat-matching) | Fitur eksperimental berat |
| Wear OS bridge, TV app, widget home-screen, browser extension | Target platform/permukaan tambahan, di luar scope "aplikasi Android inti" |
| Sign-in Google/akun (opsional) | Home bisa jalan secara anonim (`anonymous=true` di search); auth hanya ditambahkan balik kalau dibutuhkan personalisasi |

## 6. Alur pengguna utama

1. **Buka app** → tab Home aktif, lihat rekomendasi.
2. **Cari lagu** → tab Search, ketik, tap hasil → lagu mulai main, mini player
   muncul.
3. **Dengar musik lokal** → tab Local, pilih tab Songs/Artists/Albums, tap
   lagu.
4. **Kontrol penuh** → tap mini player → sheet Now Playing terbuka, kontrol
   lengkap.
5. **Lanjut dengar sambil browsing** → tutup sheet (swipe down), mini player
   tetap tampil, navigasi antar tab tidak menghentikan playback.

## 7. Non-functional requirements

- Build APK harus jauh lebih kecil dari upstream (upstream menarik
  `onnxruntime`, `smbj`, `play-services-cast`, `play-services-wearable`,
  `libadb-android`, dll. yang semuanya dibuang — lihat `DESIGN.md §5`).
- Playback tidak boleh berhenti saat app di-background (foreground service
  wajib ada sejak awal).
- Local scan tidak boleh blocking UI thread (pakai `Dispatchers.IO`, sudah
  demikian di `LocalMediaRepository` upstream).
- Tidak ada ketergantungan ke backend server kustom (Listen Together backend
  dihapus total).

## 8. Metrik sukses (kualitatif, untuk proyek personal/hobi)

- Semua 4 fitur jalan stabil di 1 device test selama penggunaan harian.
- Ukuran APK debug < setengah ukuran APK upstream.
- Tidak ada crash saat transisi tab sambil lagu sedang main.
- Kode `:app` tidak lagi punya file tunggal > 50K (dibanding `MainActivity.kt`
  upstream 328K, `PlaybackService.kt` 468K).

## 9. Referensi

- Rancangan arsitektur lengkap: `DESIGN.md`
- Mockup visual 4 layar (Home/Local/Search/Player): Design canvas artifact
  terpisah (tema gelap, aksen `#FF453A`, font Inter).
- Source upstream yang jadi acuan: `github.com/kushagrasinghx/BitChord`
