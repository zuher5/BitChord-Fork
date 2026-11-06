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

- Mesin kerja tidak punya JDK/Android SDK — build dijalankan GitHub Actions
  (`ci.yml`), bukan lokal. Commit + push, lalu periksa run di tab Actions.
- Cek modul satu-satu kalau ada broken reference: `:shared` → `:sharedUi` → `:app`
- Tidak ada test suite baru yang disiapkan; kalau menambah logic baru di
  `LocalGroups`/`QueueCoordinator`, tambahkan unit test di modul yang sama
  dengan konvensi upstream (`app/src/test/java/...`).

## Konvensi kode

- Kotlin + Jetpack Compose, mengikuti gaya upstream BitChord: komentar KDoc
  ringkas di atas fungsi/kelas publik menjelaskan *kenapa*, bukan *apa*.
- State di ViewModel pakai `StateFlow`/`MutableStateFlow`, bukan `LiveData`.
- Navigasi memakai `AppTab` enum + `rememberSaveable`, **bukan**
  `AppNavigator`/`AppRoute` dari upstream (itu dirancang untuk puluhan rute,
  berlebihan untuk 3 tab + 1 sheet).
- Player: Media3 `ExoPlayer` + `MediaSessionService` standar. Jangan tambahkan
  audio processor kustom (equalizer, crossfade, dsb.) kecuali diminta.

## CI/CD, keystore & release

Repo ini **wajib** punya pipeline build-release otomatis lewat GitHub Actions,
bukan build manual dari laptop developer. Kalau kamu (agent) ditugaskan
menyiapkan atau memperbaiki rilis, ikuti alur ini — jangan improvisasi alur
baru tanpa alasan kuat.

### 1. Keystore (upload key)

- **Jangan pernah membuat keystore dengan password hardcoded di file yang
  di-commit.** Keystore adalah rahasia jangka panjang — hilang/ganti keystore
  berarti user existing tidak bisa update app lagi.
- Generate sekali via `scripts/generate-keystore.sh` (sudah disediakan di
  repo ini). Script akan minta input alias & password secara interaktif
  (`keytool -genkeypair`), lalu keluarkan file `upload-keystore.jks` di
  root — **file ini harus langsung masuk `.gitignore`, jangan pernah commit.**
- Simpan keystore sebagai GitHub Actions secret dalam bentuk base64:
  ```bash
  base64 -w0 upload-keystore.jks > keystore.b64
  gh secret set KEYSTORE_BASE64 < keystore.b64
  gh secret set KEYSTORE_PASSWORD -b "<password keystore>"
  gh secret set KEY_ALIAS -b "<alias>"
  gh secret set KEY_PASSWORD -b "<password key>"
  ```
  4 secret ini (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD`) adalah kontrak yang dipakai workflow `release.yml` — jangan
  ganti nama tanpa update workflow-nya juga.
- Agent **boleh** menjalankan `scripts/generate-keystore.sh` dan menuntun
  user memasukkan secret di atas, tapi **tidak boleh** menebak/mengarang
  password sendiri — selalu minta user yang menentukan, atau generate
  password acak lalu tampilkan ke user sekali untuk disimpan (jangan dicatat
  di mana pun di repo).

### 2. `app/build.gradle.kts` — signing config

Tambahkan (kalau belum ada) blok ini, baca dari environment variable supaya
tidak ada secret yang nempel di source:

**Build hanya menyasar `arm64-v8a`.** Sebagian besar device target fork ini
adalah HP modern (64-bit), dan distribusi lewat GitHub Releases (bukan Play
Store) tidak butuh universal APK. Ini keputusan yang disengaja — kalau ada
kebutuhan dukung `armeabi-v7a`/`x86_64` lagi di masa depan, itu harus
permintaan eksplisit, bukan default.

```kotlin
defaultConfig {
    ndk {
        abiFilters += "arm64-v8a"   // satu-satunya ABI yang dibuild — jangan tambah tanpa diminta
    }
}

signingConfigs {
    create("release") {
        storeFile = file(System.getenv("KEYSTORE_PATH") ?: "upload-keystore.jks")
        storePassword = System.getenv("KEYSTORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS")
        keyPassword = System.getenv("KEY_PASSWORD")
    }
}
buildTypes {
    getByName("release") {
        signingConfig = signingConfigs.getByName("release")
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
}
```

### 3. Dua workflow GitHub Actions (sudah disediakan di `.github/workflows/`)

- **`ci.yml`** — jalan di setiap push & PR ke `main`: `./gradlew :app:assembleDebug`,
  lint, unit test. Tujuannya cepat gagal kalau ada yang rusak, **tidak**
  menyentuh keystore sama sekali.
- **`release.yml`** — jalan hanya saat tag `v*.*.*` di-push: decode keystore
  dari secret, `./gradlew :app:assembleRelease` (menghasilkan satu APK
  `arm64-v8a`, bukan AAB — tidak perlu AAB karena tidak distribusi lewat
  Play Store), lalu publish GitHub Release dengan APK itu sebagai asset dan
  changelog otomatis dari commit sejak tag sebelumnya.

### 4. Alur rilis yang benar (yang harus diikuti agent maupun manusia)

1. Pastikan `versionName`/`versionCode` di `app/build.gradle.kts` sudah naik
   dari rilis terakhir (manual, bukan auto — supaya sengaja/terkontrol).
2. `git tag v1.2.0 && git push origin v1.2.0`.
3. `release.yml` otomatis build signed release + bikin GitHub Release draft
   berisi APK.
4. Agent **tidak** mem-publish Release secara otomatis sampai manusia
   review draft-nya (workflow membuat release sebagai **draft**, lihat
   `draft: true` di `release.yml`) — kecuali user eksplisit minta auto-publish.
5. Setelah direview, publish manual lewat GitHub UI atau `gh release edit <tag> --draft=false`.

### 5. Yang tidak boleh dilakukan agent tanpa izin eksplisit

- Mengubah `draft: true` jadi `false` (auto-publish tanpa review manusia).
- Menghapus atau regenerate keystore yang sudah dipakai rilis sebelumnya
  (akan mematahkan update path user yang sudah install).
- Commit file `.jks`/`.keystore` atau nilai password apa pun ke git history.

## Kalau menambah dependency baru

Cek dulu apakah dependency itu ada di daftar "dibuang" (`DESIGN.md §5`). Kalau
ya dan memang dibutuhkan lagi, catat alasannya di PR description — jangan
tambah diam-diam, karena filosofi fork ini adalah *footprint sekecil mungkin*.

## Referensi desain visual

Mockup 4 layar (Home/Local/Search/Player) ada di artifact Design canvas yang
sudah dibuat terpisah — pakai sebagai acuan warna (`#0B0B0C` bg, `#FF453A`
aksen, font Inter) dan layout (tab bar bawah, mini player mengambang di atas
tab bar, now-playing sebagai sheet penuh layar).
