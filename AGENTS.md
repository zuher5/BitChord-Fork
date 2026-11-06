# AGENTS.md

Panduan untuk AI coding agent (Claude Code, dsb.) yang mengerjakan repo ini.
Repo ini adalah **fork minimalis BitChord** (upstream: `kushagrasinghx/BitChord`),
dipangkas ke 4 fitur: **Home, Local, Search, Player**. Baca `PRD.md` untuk scope
produk dan `DESIGN.md` untuk arsitektur sebelum mengubah kode apa pun.

## Aturan paling penting

1. **Jangan tambah fitur di luar 4 scope** (Home/Local/Search/Player) tanpa
   diminta eksplisit — termasuk: Spotify, Discord RPC, scrobbling, Listen
   Together, SMB/WebDAV, cast, equalizer, bit-perfect DAC, automix/loopmix,
   lyrics, download/offline, wear bridge, widget home-screen. Fitur-fitur ini
   **sengaja dihapus** dari fork ini, lihat `DESIGN.md §2`.
2. **Jangan trim ulang `YtMusicRepository` atau `LocalMediaRepository` dari nol.**
   Keduanya dipertahankan apa adanya dari upstream (minus pemanggil fungsi yang
   tidak dipakai) karena logic Innertube/MediaStore-nya sensitif. Kalau ada bug,
   perbaiki in-place, jangan tulis ulang.
3. **Jangan tulis ulang `MainActivity.kt` dari upstream (328K baris campur).**
   Struktur UI fork ini ditulis dari nol mengikuti `DESIGN.md §4`, bukan hasil
   trimming file upstream tersebut.
4. **Pertahankan pola 3-modul Gradle**: `:shared` (data/network), `:sharedUi`
   (komponen desain), `:app` (UI Android + player). Jangan gabungkan jadi satu
   modul flat.

## Struktur repo

```
app/src/main/java/com/music/bitchord/
├── MainActivity.kt
├── data/
│   ├── model/
│   ├── LocalMediaRepository.kt
│   └── LocalGroups.kt
├── playback/
│   ├── PlaybackService.kt
│   └── PlayerConnection.kt
└── ui/
    ├── screens/  (HomeScreen, LocalScreen, SearchScreen, NowPlayingScreen)
    └── components/  (MiniPlayer, TabBar, music/Lockups)
shared/src/.../YtMusicRepository.kt
```

## Build & verifikasi

- **Build dilakukan penuh di GitHub (GitHub Actions), bukan lokal.** Mesin
  kerja tidak punya JDK/Android SDK — jangan coba `./gradlew` di lokal.
  Commit + push perubahan, biarkan workflow CI yang menjalankan build penuh,
  lalu periksa hasilnya (`gh run view` atau tab Actions).
- Workflow CI (`.github/workflows/build.yml`) menjalankan build lengkap:
  `:shared` → `:sharedUi` → `:app:assembleDebug` → `:app:assembleRelease`
  dengan JDK 17 + Android SDK dari runner.
- **Output akhir = GitHub Release rolling `latest`** (bukan sekadar artifact):
  tiap push `main` yang hijau menerbitkan/mengganti release `latest` berisi
  APK prod release. APK debug + release juga tersedia sebagai artifact run.
- **Hanya arm64-v8a** (`splits.abi` di `app/build.gradle.kts`, tanpa universal
  APK). Tidak ada kode native di fork ini, jadi ABI lain hanya duplikat —
  jangan tambahkan kembali `armeabi-v7a`/`x86_64` tanpa alasan kuat.
- **Signing lewat GitHub Secrets**: `KEYSTORE_BASE64` (keystore .jks,
  base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Workflow
  menulis `keystore.properties` otomatis dari secrets, dan step
  `Verify release signing` (apksigner) **menggagalkan build** bila APK
  release keluar unsigned — unsigned tidak boleh sampai ke GitHub Release
  (itu penyebab `INSTALL_PARSE_FAILED_NO_CERTIFICATES` saat instalasi).
- **Optimasi ukuran wajib dijaga**: R8 minify + shrinkResources aktif di
  release; `resourceConfigurations` hanya `en` + `id`; tanpa jniLibs/FFmpeg;
  dependency out-of-scope tetap terlarang (`DESIGN.md §5`). Kalau menambah
  dependency/resource, sebutkan dampaknya ke ukuran APK di PR.
- Cek modul satu-satu kalau ada broken reference: `:shared` → `:sharedUi` → `:app`
- Tidak ada test suite baru yang disiapkan; kalau menambah logic baru di
  `LocalGroups`/`QueueCoordinator`, tambahkan unit test di modul yang sama
  dengan konvensi upstream (`app/src/test/java/...`).
- Jangan klaim build/release selesai sebelum workflow GitHub Actions hijau
  DAN release `latest` berisi APK dari commit terbaru.

## Konvensi kode

- Kotlin + Jetpack Compose, mengikuti gaya upstream BitChord: komentar KDoc
  ringkas di atas fungsi/kelas publik menjelaskan *kenapa*, bukan *apa*.
- State di ViewModel pakai `StateFlow`/`MutableStateFlow`, bukan `LiveData`.
- Navigasi memakai `AppTab` enum + `rememberSaveable`, **bukan**
  `AppNavigator`/`AppRoute` dari upstream (itu dirancang untuk puluhan rute,
  berlebihan untuk 3 tab + 1 sheet).
- Player: Media3 `ExoPlayer` + `MediaSessionService` standar. Jangan tambahkan
  audio processor kustom (equalizer, crossfade, dsb.) kecuali diminta.

## Kalau menambah dependency baru

Cek dulu apakah dependency itu ada di daftar "dibuang" (`DESIGN.md §5`). Kalau
ya dan memang dibutuhkan lagi, catat alasannya di PR description — jangan
tambah diam-diam, karena filosofi fork ini adalah *footprint sekecil mungkin*.

## Referensi desain visual

Mockup 4 layar (Home/Local/Search/Player) ada di artifact Design canvas yang
sudah dibuat terpisah — pakai sebagai acuan warna (`#0B0B0C` bg, `#FF453A`
aksen, font Inter) dan layout (tab bar bawah, mini player mengambang di atas
tab bar, now-playing sebagai sheet penuh layar).
