package com.music.bitchord.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.media3.common.Player
import com.music.bitchord.data.sources.SourceKind
import kotlinx.coroutines.flow.MutableStateFlow


/**
 * Whether a stream started under this ceiling may be served by [kind].
 *
 * Asked per stream rather than written into
 * [SourceConfig.enabled][com.music.bitchord.data.sources.SourceConfig.enabled],
 * which is what this used to do — an `applyQualityPreset` call flipped the
 * source switches the moment a rung was picked. Two things
 * were wrong with that and both were reported together: picking a rung for
 * *mobile data* turned the sources off while sitting on Wi-Fi, and nothing
 * turned them back on when the connection changed, so a Wi-Fi ceiling of
 * Lossless still had no lossless source to reach. A ceiling is a property
 * of the connection in force; the switches on the Sources screen are the
 * user's standing choice. Storing the first in the second lost the second.
 *
 * [SourceKind.YOUTUBE] is permitted on every rung: it is what [maxKbps]
 * caps, and it is the only source that can answer at all when the ones
 * above it are skipped.
 */
fun AudioQuality.permits(kind: SourceKind): Boolean = when (this) {
    AudioQuality.LOSSLESS -> true
    // No lossless answer is wanted here, and a source that can serve one is
    // the slow half of the list: one fronting several catalogues walks
    // all of them before it answers, which is seconds spent to land on a
    // 320kbps transcode.
    AudioQuality.HIGH -> !kind.canServeLossless
    AudioQuality.MEDIUM, AudioQuality.LOW -> kind == SourceKind.YOUTUBE
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark")
}

/**
 * How the app's SF Pro Display is set. [DEFAULT] draws each weight one cut
 * lighter and spaces it to Apple Music's own screens; [LEGACY] is the cut the
 * app shipped with before that, every weight its own and spaced tight.
 */
enum class AppFont { DEFAULT, LEGACY }

/** Stable persisted ordering for each on-device music library. */
enum class LocalMusicSort {
    TITLE_ASC,
    TITLE_DESC,
    DATE_ADDED,
    DATE_MODIFIED,
}

/**
 * Ordering for the track list on an album or playlist page, with a date option
 * for the one thing a catalogue row can still be dated by: the position it sits
 * at. A playlist's running order is the order songs were added in — YouTube
 * Music appends each addition at the foot — so read backwards it *is* a date
 * order, newest first. DetailScreen.kt holds the sort itself. Persisted
 * app-wide rather than per page: one choice, kept until the user makes another.
 */
enum class SongSort {
    DEFAULT,
    TITLE_ASC,
    TITLE_DESC,
    DATE_ADDED_ASC,
    DATE_ADDED_DESC,
}

/**
 * App settings, backed by SharedPreferences and exposed as flows.
 *
 * PlaybackService runs in the same process as the UI, so it observes these
 * same flows and applies changes to the live ExoPlayer instance immediately —
 * no restart, no rebinding.
 */
object AppSettings {

    private lateinit var prefs: SharedPreferences

    /**
     * Quality ceilings, one per kind of connection — the point of the split is
     * that Wi-Fi can stay on Lossless while mobile data is capped. Both
     * default to Lossless; the mobile plan is the user's to budget, not ours
     * to assume.
     */
    val audioQualityWifi = MutableStateFlow(AudioQuality.LOSSLESS)
    val audioQualityCellular = MutableStateFlow(AudioQuality.LOSSLESS)

    /** Allowed duration difference when replacing a playing stream with one from a source. */
    val upgradeLengthSlackSeconds = MutableStateFlow(DEFAULT_UPGRADE_LENGTH_SLACK_SECONDS)

    /** Whether the active network charges for data. `null` while offline. */
    val meteredConnection = MutableStateFlow<Boolean?>(null)

    // `losslessAudio` used to live here, behind a "Prefer lossless" switch on
    // the Sources screen. It is gone: sources are asked for their best and each
    // degrades on its own terms, so the switch's only real effect was to ask a
    // source for a worse file than it was holding. See
    // [SourceResolver.requestForNow][com.music.bitchord.data.sources.SourceResolver.requestForNow],
    // which now reads [effectiveAudioQuality] and nothing else.

    val skipSilence = MutableStateFlow(false)

    /**
     * Level every track to the same loudness, using YouTube's own
     * normalization figure for it — see
     * [com.music.bitchord.playback.PlaybackService.setupLoudnessEnhancer].
     *
     * On by default, which is the one genuinely contentious thing about it.
     * The case for it: a queue drawn from several sources is a queue of
     * several mastering eras, and the gap between a 1980s CD transfer and a
     * modern master is routinely fifteen decibels — loud enough that the
     * listener's own volume control is the wrong tool, because the setting
     * that suits one track hurts at the next. Every streaming service
     * normalizes by default for the same reason.
     *
     * The case against it is that a constant gain is still a multiplication,
     * so this is the first thing in BitChord that is *on* out of the box and
     * alters samples. Rather than hide that, the Audio Pipeline readout names
     * it: its Bit-exact row reports the first stage in the chain that is
     * altering samples, and switching this off is the first thing a listener
     * chasing an untouched signal would do.
     */
    val loudnessNormalization = MutableStateFlow(true)

    /** Skip loudness normalization while the active output is the phone's own speaker. */
    val loudnessOffOnSpeaker = MutableStateFlow(true)

    /**
     * Whether a source offering a Dolby Atmos rendition is allowed to serve it.
     *
     * On by default: where the device can decode it, Atmos is the premium
     * rendition the catalogue holds and the one most people are paying a
     * subscription for.
     *
     * Off is a real preference and not just a safety valve. Atmos is E-AC-3,
     * which is *lossy* — a track with an Atmos master is frequently also held
     * as a FLAC, and someone listening on wired headphones may well prefer the
     * bit-exact stereo copy to a spatial mix their output can't render.
     *
     * Independent of whether the device *can* decode it — that question is
     * [DeviceCodecs.playsDolbyAtmos][com.music.bitchord.data.sources.DeviceCodecs],
     * and the two are deliberately not folded together: this one is the
     * listener's answer, is persisted, and must survive being read on a phone
     * that cannot honour it (a restored backup, a swapped device) without
     * quietly rewriting itself.
     */
    val dolbyAtmos = MutableStateFlow(true)

    /**
     * Play a YouTube-queued track straight from a source ranked above YouTube,
     * rather than starting it on YouTube and swapping up mid-song.
     *
     * Also narrows read-ahead to the next track only, and only once the
     * playing one is within [DirectSourceGate.WINDOW_MS][com.music.bitchord.playback.DirectSourceGate.WINDOW_MS]
     * of its transition — see [DirectSourceGate][com.music.bitchord.playback.DirectSourceGate].
     */
    val directSourceStream = MutableStateFlow(false)

    val playbackSpeed = MutableStateFlow(1.0f)
    val themeMode = MutableStateFlow(ThemeMode.DARK)
    val appFont = MutableStateFlow(AppFont.DEFAULT)

    /** Keep playing similar music once the queue runs out. */
    val autoplay = MutableStateFlow(true)

    /** Whether the queue is held in shuffled order (Mix button). */
    val shuffleEnabled = MutableStateFlow(false)

    /** Repeat mode for the player — Off, All, or One. */
    val repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)

    /** Put the playing track's codec, bitrate and sample rate on the player. */
    val showNerdStats = MutableStateFlow(false)

    /** Freezes the main player's mesh gradient instead of letting it drift/crossfade. */
    val reduceAnimation = MutableStateFlow(false)

    /**
     * Whether the welcome pages have been through. Absent rather than true for
     * anyone updating from a build that had no onboarding, so they get it once
     * too.
     */
    val onboardingComplete = MutableStateFlow(false)

    /** Requests a sustained high-refresh UI. Off keeps Android's automatic policy. */
    val highPerformanceMode = MutableStateFlow(false)

    /** Preferred UI refresh rate while [highPerformanceMode] is enabled. */
    val performanceRefreshRate = MutableStateFlow(DEFAULT_PERFORMANCE_REFRESH_RATE)

    /** Stop playback when the app is swiped away from the recent apps screen. */
    val stopOnTaskRemoved = MutableStateFlow(true)

    /** Hides the volume slider on the main player, leaving the rest of the layout to reflow. */
    val hideVolumeBar = MutableStateFlow(false)

    /** Hides the device status bar (clock, battery, notifications) while the main player is open. */
    val hideStatusBarInPlayer = MutableStateFlow(false)

    /** Hides the "Playing from" / "Played by" caption at the top of the main player. */
    val hideSongStatus = MutableStateFlow(false)

    val showQueueHistory = MutableStateFlow(false)

    /** Swiping a song row plays it next instead of adding it to the end of the queue. */
    val swipeToPlayNext = MutableStateFlow(false)

    /** Once a song has been suggested or played this session, AutoPlay won't offer it again. */
    val dontRepeatSuggestions = MutableStateFlow(false)

    /** Drops haze blur (status bar, mini player, bottom fade) for a solid-fill look. */
    val reduceDynamicBlur = MutableStateFlow(false)

    /** Real backdrop-sampled glass (blur, lens refraction) on the floating nav bar, Android 12+ only. */
    val liquidGlass = MutableStateFlow(false)

    /**
     * Blurs what scrolls under the status bar and the top controls, easing out
     * just below them, instead of fading it to the page colour. A Liquid Glass
     * option, honoured only while [liquidGlass] is on. Off by default: it costs
     * frames. Reduce dynamic blur overrides it.
     */
    val progressiveBlur = MutableStateFlow(false)

    /**
     * Blows the player's cover art out to a full-bleed banner running off the
     * top of the screen, rather than sitting it in a square card.
     *
     * The treatment motion artwork has always had, applied to still sleeves too.
     * Off restores the card: the sleeve keeps its corners, its shadow and its
     * shrink-while-paused, and only a clip goes full-bleed. Phones only either
     * way — see the hero notes in
     * [NowPlayingScreen][com.music.bitchord.ui.player.NowPlayingScreen].
     */
    val fullBleedArtwork = MutableStateFlow(true)

    /**
     * Puts v1.5's backdrop back on the player: four quantised blobs drifting
     * behind the whole screen, rather than the artwork's own colours hung off
     * the sleeve's bottom edge.
     *
     * Off by default, because the current backdrop replaced it for two reasons
     * that have not gone away — see [ArtworkMesh][com.music.bitchord.ui.player.ArtworkMesh]
     * for the colour one (a cover that is nine-tenths black with a red stripe
     * comes back from the quantiser as a red screen) and
     * [ArtworkMeshBackdrop][com.music.bitchord.ui.player.ArtworkMeshBackdrop]
     * for the cost one (blobs that drift are a full-screen blur redrawn while
     * they move, where a mesh is drawn once per track and then composited).
     * Kept as a switch because people asked for the old look back, and neither
     * reason is one a listener has to agree with.
     */
    val legacyMeshGradient = MutableStateFlow(false)

    /** Restores the expanded player to the surface the listener left open. */
    val lastPlayerScreen = MutableStateFlow(LastPlayerScreen.MAIN)

    /**
     * Disk budget for cached audio. [AudioCache][com.music.bitchord.playback.AudioCache] evicts past it.
     * [UNLIMITED_CACHE_LIMIT_BYTES] means no ceiling of the app's own.
     */
    val audioCacheLimitBytes = MutableStateFlow(DEFAULT_CACHE_LIMIT_BYTES)

    /**
     * Whether Library's "On device" shelf carries the Cached songs folder —
     * the YouTube tracks the song cache is holding. Off by
     * default: the cache is an implementation detail most people never need
     * to look inside.
     */
    val showCacheFolder = MutableStateFlow(false)

    // ── Library ─────────────────────────────────────────────────────────────

    /** Hides short clips, recorder output and non-music formats from Local Music. */
    val filterNonMusicAudio = MutableStateFlow(true)

    val localMusicSort = MutableStateFlow(LocalMusicSort.TITLE_ASC)
    val localMusicViewType = MutableStateFlow(LibraryViewType.LIST)
    /** Layout used by the Recents shelf on Play; compact tracks are the default. */
    val librarySort = MutableStateFlow(LibrarySort.DEFAULT)

    /**
     * Each album/playlist page's track-list order, keyed by browse id —
     * Spotify-style, every page keeps its own. A page never touched reads as
     * [SongSort.DEFAULT].
     */
    val detailSongSorts = MutableStateFlow<Map<String, SongSort>>(emptyMap())

    /** Empty means every MediaStore folder; otherwise this is a persisted SAF tree URI. */
    val localMusicFolderUri = MutableStateFlow("")

    /**
     * Browse ids of the playlists pinned to the top of the Library tab, in the
     * order they were pinned.
     *
     * A [List] rather than a [Set]: pin order is part of what a pin means here —
     * the whole point is a small, hand-picked front row, and a set would leave
     * that order to hash iteration. Capped at [MAX_PINNED_PLAYLISTS] by
     * [togglePinnedPlaylist], the only way this is ever written.
     */
    val pinnedPlaylists = MutableStateFlow<List<String>>(emptyList())

    /** How many playlists [pinnedPlaylists] can hold at once. */
    const val MAX_PINNED_PLAYLISTS = 5

    /** Published by PlaybackService so the UI can open the system equalizer. */
    val audioSessionId = MutableStateFlow(0)

    /**
     * A temporary cap on [effectiveAudioQuality], for as long as something other
     * than this phone's own speaker is playing — the BitChord watch app, whose
     * audio goes over a Bluetooth link that cannot keep up with lossless. Never
     * persisted, never shown: the listener's own settings are untouched, and
     * apply again the moment the phone takes the music back.
     */
    val streamCeilingOverride = MutableStateFlow<AudioQuality?>(null)

    /** The ceiling that applies to a stream started right now. */
    val effectiveAudioQuality: AudioQuality
        get() = ceilingFor(
            wifi = audioQualityWifi.value,
            cellular = audioQualityCellular.value,
            metered = meteredConnection.value,
            override = streamCeilingOverride.value,
        )

    /**
     * [effectiveAudioQuality] from values a caller already holds — a screen
     * that collected the four flows so it recomposes when any of them moves.
     */
    fun ceilingFor(
        wifi: AudioQuality,
        cellular: AudioQuality,
        metered: Boolean?,
        override: AudioQuality?,
    ): AudioQuality {
        val chosen = if (metered == true) cellular else wifi
        val cap = override ?: return chosen
        return if (chosen.ordinal > cap.ordinal) cap else chosen
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
        readAll()
        watchConnection(context)
    }

    /**
     * Re-reads every setting off disk.
     *
     * The one caller is an import, which writes the whole preference file
     * underneath these flows. Nothing else in the app changes a preference
     * without going through the setter beside it, so nothing else has a reason
     * to ask.
     *
     * Deliberately not re-registering the network callback: that watches the
     * device, not the preferences, and a second one would have both firing.
     */
    fun reload() {
        if (!this::prefs.isInitialized) return
        readAll()
    }

    private fun readAll() {
        migrateSingleQuality()
        audioQualityWifi.value = readQuality(KEY_QUALITY_WIFI)
        audioQualityCellular.value = readQuality(KEY_QUALITY_CELLULAR)
        upgradeLengthSlackSeconds.value = prefs.getInt(
            KEY_UPGRADE_LENGTH_SLACK_SECONDS,
            DEFAULT_UPGRADE_LENGTH_SLACK_SECONDS,
        ).coerceIn(MIN_UPGRADE_LENGTH_SLACK_SECONDS, MAX_UPGRADE_LENGTH_SLACK_SECONDS)
        skipSilence.value = prefs.getBoolean(KEY_SKIP_SILENCE, false)
        loudnessNormalization.value = prefs.getBoolean(KEY_LOUDNESS_NORMALIZATION, true)
        loudnessOffOnSpeaker.value = prefs.getBoolean(KEY_LOUDNESS_OFF_ON_SPEAKER, true)
        dolbyAtmos.value = prefs.getBoolean(KEY_DOLBY_ATMOS, true)
        directSourceStream.value = prefs.getBoolean(KEY_DIRECT_SOURCE_STREAM, false)
        playbackSpeed.value = prefs.getFloat(KEY_SPEED, 1.0f)
        themeMode.value = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "DARK")
        }.getOrDefault(ThemeMode.DARK)
        appFont.value = runCatching {
            AppFont.valueOf(prefs.getString(KEY_APP_FONT, null) ?: AppFont.DEFAULT.name)
        }.getOrDefault(AppFont.DEFAULT)
        autoplay.value = prefs.getBoolean(KEY_AUTOPLAY, true)
        shuffleEnabled.value = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false)
        repeatMode.value = prefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF)
        showNerdStats.value = prefs.getBoolean(KEY_NERD_STATS, false)
        reduceAnimation.value = prefs.getBoolean(KEY_REDUCE_ANIMATION, false)
        onboardingComplete.value = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        highPerformanceMode.value = prefs.getBoolean(KEY_HIGH_PERFORMANCE_MODE, false)
        performanceRefreshRate.value = normalizePerformanceRefreshRate(
            prefs.getInt(KEY_PERFORMANCE_REFRESH_RATE, DEFAULT_PERFORMANCE_REFRESH_RATE),
        )
        stopOnTaskRemoved.value = prefs.getBoolean(KEY_STOP_ON_TASK_REMOVED, true)
        hideVolumeBar.value = prefs.getBoolean(KEY_HIDE_VOLUME_BAR, false)
        hideStatusBarInPlayer.value = prefs.getBoolean(KEY_HIDE_STATUS_BAR_IN_PLAYER, false)
        hideSongStatus.value = prefs.getBoolean(KEY_HIDE_SONG_STATUS, false)
        showQueueHistory.value = prefs.getBoolean(KEY_SHOW_QUEUE_HISTORY, false)
        swipeToPlayNext.value = prefs.getBoolean(KEY_SWIPE_TO_PLAY_NEXT, false)
        dontRepeatSuggestions.value = prefs.getBoolean(KEY_DONT_REPEAT_SUGGESTIONS, false)
        reduceDynamicBlur.value = prefs.getBoolean(KEY_REDUCE_BLUR, false)
        liquidGlass.value = prefs.getBoolean(KEY_LIQUID_GLASS, false)
        progressiveBlur.value = prefs.getBoolean(KEY_PROGRESSIVE_BLUR, false)
        if (highPerformanceMode.value) {
            reduceAnimation.value = false
            reduceDynamicBlur.value = false
        }
        fullBleedArtwork.value = prefs.getBoolean(KEY_FULL_BLEED_ARTWORK, true)
        legacyMeshGradient.value = prefs.getBoolean(KEY_LEGACY_MESH_GRADIENT, false)
        lastPlayerScreen.value = runCatching {
            LastPlayerScreen.valueOf(
                prefs.getString(KEY_LAST_PLAYER_SCREEN, null) ?: LastPlayerScreen.MAIN.name,
            )
        }.getOrDefault(LastPlayerScreen.MAIN)
        audioCacheLimitBytes.value = clampCacheLimit(prefs.getLong(KEY_CACHE_LIMIT, DEFAULT_CACHE_LIMIT_BYTES))
        showCacheFolder.value = prefs.getBoolean(KEY_SHOW_CACHE_FOLDER, false)
        filterNonMusicAudio.value = prefs.getBoolean(KEY_FILTER_NON_MUSIC_AUDIO, true)
        localMusicSort.value = readLocalMusicSort(KEY_LOCAL_MUSIC_SORT)
        localMusicViewType.value = readLibraryViewType(KEY_LOCAL_MUSIC_VIEW_TYPE)
        librarySort.value = prefs.getString(KEY_LIBRARY_SORT, null)
            ?.let { saved -> LibrarySort.entries.firstOrNull { it.name == saved } }
            ?: LibrarySort.DEFAULT
        detailSongSorts.value = readDetailSongSorts()
        localMusicFolderUri.value = prefs.getString(KEY_LOCAL_MUSIC_FOLDER_URI, "").orEmpty()
        pinnedPlaylists.value = readPinnedPlaylists()
    }

    /**
     * True the first time this is called after [currentVersionCode] rises above
     * whatever was last recorded — i.e. once per update, on the first launch
     * after it installs. A fresh install has nothing to compare against, so
     * the very first call seeds the stored value from [currentVersionCode]
     * rather than reporting an update.
     *
     * BitChord ships sideloaded (see [com.music.bitchord.data.AppUpdateChecker]),
     * so installing a new APK over the old one is the only "update" there is —
     * app data, this pref included, survives it exactly like a Play Store
     * update. Call once per process start, before anything reads a cache that
     * an update should invalidate.
     */
    fun consumeVersionUpdate(currentVersionCode: Int): Boolean {
        val last = prefs.getInt(KEY_LAST_VERSION_CODE, currentVersionCode)
        if (last != currentVersionCode) {
            prefs.edit().putInt(KEY_LAST_VERSION_CODE, currentVersionCode).apply()
        }
        return currentVersionCode > last
    }

    /**
     * A ceiling saved when there was only one applies to both connections.
     * Someone who picked Low to protect a data plan would not thank us for
     * quietly putting Wi-Fi *and* mobile back on High.
     */
    private fun migrateSingleQuality() {
        val legacy = prefs.getString(KEY_QUALITY_LEGACY, null) ?: return
        prefs.edit()
            .putString(KEY_QUALITY_WIFI, legacy)
            .putString(KEY_QUALITY_CELLULAR, legacy)
            .remove(KEY_QUALITY_LEGACY)
            .apply()
    }

    private fun readQuality(key: String): AudioQuality {
        val stored = prefs.getString(key, null) ?: return AudioQuality.LOSSLESS
        return runCatching { AudioQuality.valueOf(stored) }.getOrDefault(AudioQuality.LOSSLESS)
    }

    /**
     * Tracks whether the active network is metered, so quality-dependent
     * decisions can follow the connection without a per-request probe.
     */
    private fun watchConnection(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val refresh = {
            meteredConnection.value = runCatching {
                if (manager.activeNetwork == null) null else manager.isActiveNetworkMetered
            }.getOrNull()
        }
        refresh()
        runCatching {
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = refresh()
                    override fun onLost(network: Network) = refresh()
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) = refresh()
                },
            )
        }
    }

    fun setAutoplay(value: Boolean) {
        autoplay.value = value
        prefs.edit().putBoolean(KEY_AUTOPLAY, value).apply()
    }

    fun setShuffleEnabled(value: Boolean) {
        shuffleEnabled.value = value
        prefs.edit().putBoolean(KEY_SHUFFLE_ENABLED, value).apply()
    }

    fun setRepeatMode(value: Int) {
        repeatMode.value = value
        prefs.edit().putInt(KEY_REPEAT_MODE, value).apply()
    }

    fun setAudioQualityWifi(value: AudioQuality) {
        audioQualityWifi.value = value
        prefs.edit().putString(KEY_QUALITY_WIFI, value.name).apply()
    }

    fun setAudioQualityCellular(value: AudioQuality) {
        audioQualityCellular.value = value
        prefs.edit().putString(KEY_QUALITY_CELLULAR, value.name).apply()
    }

    fun setUpgradeLengthSlackSeconds(value: Int) {
        val normalized = value.coerceIn(
            MIN_UPGRADE_LENGTH_SLACK_SECONDS,
            MAX_UPGRADE_LENGTH_SLACK_SECONDS,
        )
        upgradeLengthSlackSeconds.value = normalized
        prefs.edit().putInt(KEY_UPGRADE_LENGTH_SLACK_SECONDS, normalized).apply()
    }

    fun setSkipSilence(value: Boolean) {
        skipSilence.value = value
        prefs.edit().putBoolean(KEY_SKIP_SILENCE, value).apply()
    }

    fun setDolbyAtmos(value: Boolean) {
        dolbyAtmos.value = value
        prefs.edit().putBoolean(KEY_DOLBY_ATMOS, value).apply()
    }

    fun setDirectSourceStream(value: Boolean) {
        directSourceStream.value = value
        prefs.edit().putBoolean(KEY_DIRECT_SOURCE_STREAM, value).apply()
    }

    fun setPlaybackSpeed(value: Float) {
        playbackSpeed.value = value
        prefs.edit().putFloat(KEY_SPEED, value).apply()
    }

    fun setShowNerdStats(value: Boolean) {
        showNerdStats.value = value
        prefs.edit().putBoolean(KEY_NERD_STATS, value).apply()
    }

    fun setThemeMode(value: ThemeMode) {
        themeMode.value = value
        prefs.edit().putString(KEY_THEME, value.name).apply()
    }

    fun setAppFont(value: AppFont) {
        appFont.value = value
        prefs.edit().putString(KEY_APP_FONT, value.name).apply()
    }

    fun setReduceAnimation(value: Boolean) {
        reduceAnimation.value = value
        if (value) highPerformanceMode.value = false
        val editor = prefs.edit().putBoolean(KEY_REDUCE_ANIMATION, value)
        if (value) editor.putBoolean(KEY_HIGH_PERFORMANCE_MODE, false)
        editor.apply()
    }

    fun setOnboardingComplete() {
        onboardingComplete.value = true
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
    }

    fun setStopOnTaskRemoved(value: Boolean) {
        stopOnTaskRemoved.value = value
        prefs.edit().putBoolean(KEY_STOP_ON_TASK_REMOVED, value).apply()
    }

    fun setHideVolumeBar(value: Boolean) {
        hideVolumeBar.value = value
        prefs.edit().putBoolean(KEY_HIDE_VOLUME_BAR, value).apply()
    }

    fun setHideStatusBarInPlayer(value: Boolean) {
        hideStatusBarInPlayer.value = value
        prefs.edit().putBoolean(KEY_HIDE_STATUS_BAR_IN_PLAYER, value).apply()
    }

    fun setHideSongStatus(value: Boolean) {
        hideSongStatus.value = value
        prefs.edit().putBoolean(KEY_HIDE_SONG_STATUS, value).apply()
    }

    fun setShowQueueHistory(value: Boolean) {
        showQueueHistory.value = value
        prefs.edit().putBoolean(KEY_SHOW_QUEUE_HISTORY, value).apply()
    }

    fun setSwipeToPlayNext(value: Boolean) {
        swipeToPlayNext.value = value
        prefs.edit().putBoolean(KEY_SWIPE_TO_PLAY_NEXT, value).apply()
    }

    fun setDontRepeatSuggestions(value: Boolean) {
        dontRepeatSuggestions.value = value
        prefs.edit().putBoolean(KEY_DONT_REPEAT_SUGGESTIONS, value).apply()
    }

    fun setReduceDynamicBlur(value: Boolean) {
        reduceDynamicBlur.value = value
        if (value) highPerformanceMode.value = false
        val editor = prefs.edit().putBoolean(KEY_REDUCE_BLUR, value)
        if (value) editor.putBoolean(KEY_HIGH_PERFORMANCE_MODE, false)
        editor.apply()
    }

    fun setLiquidGlass(value: Boolean) {
        liquidGlass.value = value
        prefs.edit().putBoolean(KEY_LIQUID_GLASS, value).apply()
    }

    fun setProgressiveBlur(value: Boolean) {
        progressiveBlur.value = value
        prefs.edit().putBoolean(KEY_PROGRESSIVE_BLUR, value).apply()
    }

    fun setHighPerformanceMode(value: Boolean) {
        highPerformanceMode.value = value
        if (value) {
            reduceAnimation.value = false
            reduceDynamicBlur.value = false
        }
        val editor = prefs.edit().putBoolean(KEY_HIGH_PERFORMANCE_MODE, value)
        if (value) {
            editor.putBoolean(KEY_REDUCE_ANIMATION, false)
            editor.putBoolean(KEY_REDUCE_BLUR, false)
        }
        editor.apply()
    }

    fun setPerformanceRefreshRate(value: Int) {
        val normalized = normalizePerformanceRefreshRate(value)
        performanceRefreshRate.value = normalized
        prefs.edit().putInt(KEY_PERFORMANCE_REFRESH_RATE, normalized).apply()
    }

    fun setFullBleedArtwork(value: Boolean) {
        fullBleedArtwork.value = value
        prefs.edit().putBoolean(KEY_FULL_BLEED_ARTWORK, value).apply()
    }

    fun setLegacyMeshGradient(value: Boolean) {
        legacyMeshGradient.value = value
        prefs.edit().putBoolean(KEY_LEGACY_MESH_GRADIENT, value).apply()
    }

    fun setLastPlayerScreen(value: LastPlayerScreen) {
        if (lastPlayerScreen.value == value) return
        lastPlayerScreen.value = value
        prefs.edit().putString(KEY_LAST_PLAYER_SCREEN, value.name).apply()
    }

    /**
     * Clamped to [DEFAULT_CACHE_LIMIT_BYTES]..[MAX_CACHE_LIMIT_BYTES] — the floor is the default, not zero.
     * Anything past [MAX_CACHE_LIMIT_BYTES] is [UNLIMITED_CACHE_LIMIT_BYTES].
     */
    fun setAudioCacheLimitBytes(value: Long) {
        val clamped = clampCacheLimit(value)
        audioCacheLimitBytes.value = clamped
        prefs.edit().putLong(KEY_CACHE_LIMIT, clamped).apply()
    }

    private fun clampCacheLimit(value: Long): Long =
        if (value > MAX_CACHE_LIMIT_BYTES) UNLIMITED_CACHE_LIMIT_BYTES
        else value.coerceAtLeast(DEFAULT_CACHE_LIMIT_BYTES)

    fun setShowCacheFolder(value: Boolean) {
        showCacheFolder.value = value
        prefs.edit().putBoolean(KEY_SHOW_CACHE_FOLDER, value).apply()
    }

    fun setLoudnessOffOnSpeaker(value: Boolean) {
        loudnessOffOnSpeaker.value = value
        prefs.edit().putBoolean(KEY_LOUDNESS_OFF_ON_SPEAKER, value).apply()
    }

    fun setLoudnessNormalization(value: Boolean) {
        loudnessNormalization.value = value
        prefs.edit().putBoolean(KEY_LOUDNESS_NORMALIZATION, value).apply()
    }


    fun setFilterNonMusicAudio(value: Boolean) {
        filterNonMusicAudio.value = value
        prefs.edit().putBoolean(KEY_FILTER_NON_MUSIC_AUDIO, value).apply()
    }

    fun setLocalMusicSort(value: LocalMusicSort) {
        localMusicSort.value = value
        prefs.edit().putString(KEY_LOCAL_MUSIC_SORT, value.name).apply()
    }

    fun setLibrarySort(value: LibrarySort) {
        librarySort.value = value
        prefs.edit().putString(KEY_LIBRARY_SORT, value.name).apply()
    }

    fun setDetailSongSort(browseId: String, value: SongSort) {
        detailSongSorts.value = detailSongSorts.value + (browseId to value)
        prefs.edit().putString(
            KEY_DETAIL_SONG_SORTS,
            detailSongSorts.value.entries.joinToString(",") { (id, sort) -> "$id=${sort.name}" },
        ).apply()
    }

    fun setLocalMusicViewType(value: LibraryViewType) {
        localMusicViewType.value = value
        prefs.edit().putString(KEY_LOCAL_MUSIC_VIEW_TYPE, value.name).apply()
    }

    fun setLocalMusicFolderUri(value: String) {
        localMusicFolderUri.value = value
        prefs.edit().putString(KEY_LOCAL_MUSIC_FOLDER_URI, value).apply()
    }

    private fun readLocalMusicSort(key: String): LocalMusicSort =
        prefs.getString(key, null)
            ?.let { saved -> LocalMusicSort.entries.firstOrNull { it.name == saved } }
            ?: LocalMusicSort.TITLE_ASC

    private fun readLibraryViewType(key: String): LibraryViewType =
        prefs.getString(key, null)
            ?.let { saved -> LibraryViewType.entries.firstOrNull { it.name == saved } }
            ?: LibraryViewType.LIST

    /**
     * Pins or unpins [browseId], returning whether it is pinned afterwards.
     *
     * Pinning past [MAX_PINNED_PLAYLISTS] is refused rather than evicting the
     * oldest pin: a silent swap would mean a playlist someone pinned on purpose
     * disappears from the row without them ever having touched it, the moment
     * they pin a sixth. Unpinning always succeeds.
     */
    fun togglePinnedPlaylist(browseId: String): Boolean {
        val current = pinnedPlaylists.value
        val updated = when {
            browseId in current -> current - browseId
            current.size >= MAX_PINNED_PLAYLISTS -> return false
            else -> current + browseId
        }
        pinnedPlaylists.value = updated
        prefs.edit().putString(KEY_PINNED_PLAYLISTS, updated.joinToString(",")).apply()
        return browseId in updated
    }

    private fun readDetailSongSorts(): Map<String, SongSort> =
        prefs.getString(KEY_DETAIL_SONG_SORTS, null)
            ?.split(",")
            ?.mapNotNull { entry ->
                val id = entry.substringBefore('=', "")
                val sort = SongSort.entries.firstOrNull { it.name == entry.substringAfter('=', "") }
                if (id.isBlank() || sort == null) null else id to sort
            }
            ?.toMap()
            ?: emptyMap()

    private fun readPinnedPlaylists(): List<String> {
        val stored = prefs.getString(KEY_PINNED_PLAYLISTS, null) ?: return emptyList()
        return stored.split(",").filter { it.isNotBlank() }
    }

    // ── Backup ──────────────────────────────────────────────────────────────

    /**
     * Every stored preference, for an export.
     *
     * Read off the preference file wholesale rather than assembled from the
     * flows above, so a setting added in a later build is in the backup the day
     * it is added instead of the day somebody remembers to list it here. What is
     * *left out* is therefore the part worth stating explicitly, and it is
     * [SECRETS]: an export is a file the user is about to put in Drive or a
     * chat, and a credential in it has left the device in plain text. Signing
     * back in after a restore is a minute; a leaked secret is not recoverable
     * at all.
     */
    fun exportPrefs(): Map<String, Any?> {
        if (!this::prefs.isInitialized) return emptyMap()
        return prefs.all.filterKeys { it !in SECRETS && it !in DEVICE_LOCAL }
    }

    /**
     * Replaces the preference file with [values] and re-reads it.
     *
     * A replace, not a merge: a partial restore leaves a device holding half of
     * one configuration and half of another, which is the one outcome nobody
     * asked for. Keys in [SECRETS] survive untouched — they were never in the
     * file being restored from, and clearing them would sign the user out of
     * services the backup has nothing to say about.
     */
    fun importPrefs(values: Map<String, Any?>) {
        if (!this::prefs.isInitialized) return
        val kept = prefs.all.filterKeys { it in SECRETS || it in DEVICE_LOCAL }
        prefs.edit().apply {
            clear()
            val incoming = values.filterKeys { it !in SECRETS && it !in DEVICE_LOCAL }
            (kept + incoming).forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> Unit
                }
            }
        }.apply()
        reload()
    }

    /**
     * Preferences an export must not carry — credentials, not configuration.
     * See [exportPrefs]. Empty in this build: the features that kept secrets
     * here are gone.
     */
    private val SECRETS = emptySet<String>()

    /**
     * Preferences that describe *this device* rather than this configuration,
     * and so are neither exported nor overwritten by an import.
     */
    private val DEVICE_LOCAL = setOf(
        KEY_LOCAL_MUSIC_FOLDER_URI,
        KEY_LAST_VERSION_CODE,
        KEY_ONBOARDING_COMPLETE,
    )

    const val DEFAULT_CACHE_LIMIT_BYTES = 512L * 1024 * 1024
    const val MAX_CACHE_LIMIT_BYTES = 10L * 1024 * 1024 * 1024

    /** The cache limit with no ceiling: only free storage bounds it. */
    const val UNLIMITED_CACHE_LIMIT_BYTES = Long.MAX_VALUE

    const val DEFAULT_UPGRADE_LENGTH_SLACK_SECONDS = 5
    const val MIN_UPGRADE_LENGTH_SLACK_SECONDS = 0
    const val MAX_UPGRADE_LENGTH_SLACK_SECONDS = 10

    private const val DEFAULT_PERFORMANCE_REFRESH_RATE = 120

    private fun normalizePerformanceRefreshRate(value: Int): Int =
        value.takeIf { it in 50..240 } ?: DEFAULT_PERFORMANCE_REFRESH_RATE


    private const val KEY_QUALITY_LEGACY = "audio_quality"
    private const val KEY_QUALITY_WIFI = "audio_quality_wifi"
    private const val KEY_QUALITY_CELLULAR = "audio_quality_cellular"
    private const val KEY_UPGRADE_LENGTH_SLACK_SECONDS = "upgrade_length_slack_seconds"
    private const val KEY_DOLBY_ATMOS = "dolby_atmos"
    private const val KEY_DIRECT_SOURCE_STREAM = "direct_source_stream"
    private const val KEY_APP_FONT = "app_font"
    private const val KEY_AUTOPLAY = "autoplay"
    private const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_NERD_STATS = "show_nerd_stats"
    private const val KEY_REDUCE_ANIMATION = "reduce_animation"
    private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    private const val KEY_HIGH_PERFORMANCE_MODE = "high_performance_mode"
    private const val KEY_PERFORMANCE_REFRESH_RATE = "performance_refresh_rate"
    private const val KEY_STOP_ON_TASK_REMOVED = "stop_on_task_removed"
    private const val KEY_HIDE_VOLUME_BAR = "hide_volume_bar"
    private const val KEY_HIDE_STATUS_BAR_IN_PLAYER = "hide_status_bar_in_player"
    private const val KEY_HIDE_SONG_STATUS = "hide_song_status"
    private const val KEY_SHOW_QUEUE_HISTORY = "show_queue_history"
    private const val KEY_SWIPE_TO_PLAY_NEXT = "swipe_to_play_next"
    private const val KEY_DONT_REPEAT_SUGGESTIONS = "dont_repeat_suggestions"
    private const val KEY_LIQUID_GLASS = "liquid_glass"
    private const val KEY_PROGRESSIVE_BLUR = "progressive_blur"

    private const val KEY_CACHE_LIMIT = "audio_cache_limit_bytes"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_SKIP_SILENCE = "skip_silence"
    private const val KEY_LOUDNESS_NORMALIZATION = "loudness_normalization"
    private const val KEY_LOUDNESS_OFF_ON_SPEAKER = "loudness_off_on_speaker"
    private const val KEY_REDUCE_BLUR = "reduce_dynamic_blur"
    private const val KEY_FULL_BLEED_ARTWORK = "full_bleed_artwork"
    private const val KEY_LEGACY_MESH_GRADIENT = "legacy_mesh_gradient"
    private const val KEY_LAST_PLAYER_SCREEN = "last_player_screen"
    private const val KEY_SHOW_CACHE_FOLDER = "show_cache_folder"
    private const val KEY_FILTER_NON_MUSIC_AUDIO = "filter_non_music_audio"
    private const val KEY_LOCAL_MUSIC_SORT = "local_music_sort"
    private const val KEY_LIBRARY_SORT = "library_sort"
    private const val KEY_DETAIL_SONG_SORTS = "detail_song_sorts"
    private const val KEY_LOCAL_MUSIC_VIEW_TYPE = "local_music_view_type"
    private const val KEY_LOCAL_MUSIC_FOLDER_URI = "local_music_folder_uri"
    private const val KEY_PINNED_PLAYLISTS = "pinned_playlists"
    private const val KEY_LAST_VERSION_CODE = "last_version_code"
}
