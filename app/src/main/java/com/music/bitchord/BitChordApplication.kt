package com.music.bitchord

import android.app.Application
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import coil3.request.crossfade
import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.innertube.AndroidStreamHooks
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.BoundedContextPersistence
import com.music.bitchord.playback.LastPlayed
import com.music.bitchord.playback.OriginalVersion
import kotlin.concurrent.thread

class BitChordApplication : Application(), SingletonImageLoader.Factory {

    @OptIn(UnstableApi::class)
    @kotlin.OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate() {
        super.onCreate()
        AndroidStreamHooks.installEarly()
        // The shared data layer logs to logcat on debug builds only.
        if (BuildConfig.DEBUG) {
            DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
                when (level) {
                    'D' -> android.util.Log.d(tag, message, error)
                    'I' -> android.util.Log.i(tag, message, error)
                    'W' -> android.util.Log.w(tag, message, error)
                    else -> android.util.Log.e(tag, message, error)
                }
            }
        } else {
            // R8 strips android.util.Log from a release build (proguard-rules.pro);
            // this stops Media3 composing the lines it would have handed it.
            androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
            java.util.logging.LogManager.getLogManager().reset()
            java.util.logging.Logger.getLogger("").level = java.util.logging.Level.OFF
        }
        // Compose re-votes the window's frame rate from dispatchDraw on every
        // frame (API 35+), and Samsung's View logs each vote together with a
        // stack walk of its caller. The app asks for its refresh rate itself,
        // so those votes buy nothing — and they kept resetting that request.
        ComposeUiFlags.isAdaptiveRefreshRateEnabled = false
        // The per-app language picker, which YouTube Music's `hl` follows.
        // Full tag (zh-Hant / zh-Hans preserved); Innertube maps to hl/gl.
        Innertube.appLanguage = { AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag() }
        migrateScriptOnlyAppLocale()
        // Opened off the main thread: SourceRegistry's store is the slowest
        // open at startup, and nothing below reads it before this joins.
        val backgroundInit = thread(name = "startup-init") {
            SourceRegistry.init(this)
            AndroidStreamHooks.initInnerTubeX(this)
        }
        // The fork streams anonymously (search/home are `anonymous=true` upstream
        // paths too), so no session cookie is installed here — Innertube works
        // without one, and auth was removed from this fork deliberately.
        AppSettings.init(this)
        SearchHistory.init(this)
        LastPlayed.init(this)
        BoundedContextPersistence.init(this)
        // Which tracks the listener has reverted to YouTube's own upload. Read
        // by [Song.toMediaItem], so it has to be open before a restart snapshot
        // is turned back into queue items.
        OriginalVersion.init(this)
        // One cache directory can only be opened once per process, and
        // PlaybackService shares this one — so it's opened here, not there.
        AudioCache.init(this)
        // A sideloaded update is just a new APK over the old one, so app data —
        // including whatever the old build left in the image cache — survives
        // it untouched. Wipe that on the first launch of a higher versionCode so
        // a format change between builds can't serve stale bytes. The song
        // cache is deliberately *not* wiped here: it is the Cached songs folder.
        if (AppSettings.consumeVersionUpdate(BuildConfig.VERSION_CODE)) {
            SingletonImageLoader.get(this).let { loader ->
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
        }
        backgroundInit.join()
    }

    /**
     * Artwork loading, sized deliberately rather than left on Coil's defaults:
     * the default disk cache is 2% of free space — on a full phone, a few
     * screens of covers. Covers are exactly the thing worth still having
     * tomorrow, so this one is named and capped at 100MB.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory())
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            // Covers arriving with a hard cut read as the list flickering as
            // it scrolls; a short fade reads as them developing.
            .crossfade(200)
            .allowHardware(!emulatedGpu)
            .build()

    /**
     * Whether graphics here go through an emulator's GL translator rather than
     * a real GPU driver. On BlueStacks (Android 9) uploading a hardware bitmap
     * aborts the whole process — `glGenTextures error! GL_INVALID_ENUM` from
     * RenderThread, each time the app came back to the foreground. Asked of
     * the driver on disk rather than of build properties, because emulators
     * spoof those; `libEGL_emulation` is the AOSP emulator's translator, which
     * no phone ships.
     */
    private val emulatedGpu: Boolean by lazy {
        listOf("/vendor/lib64", "/vendor/lib", "/system/lib64", "/system/lib")
            .any { java.io.File("$it/egl/libEGL_emulation.so").exists() }
    }

    companion object {
        /**
         * One-time move from region-less script tags to regional ones.
         *
         * Early builds of the Simplified/Traditional picker stored `zh-Hant` /
         * `zh-Hans`. Android Views resolve those (script qualifiers), but the
         * shared Compose resources only match language + region — so Settings
         * read Traditional while the player read Simplified. Regional tags
         * match on both systems. Bare `zh` is left alone: it historically
         * meant Simplified.
         */
        private fun migrateScriptOnlyAppLocale() {
            val current = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
            val replacement = when (current) {
                "zh-Hant" -> "zh-TW"
                "zh-Hans" -> "zh-CN"
                else -> return
            }
            AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(replacement),
            )
        }
    }
}
