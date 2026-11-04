package com.music.bitchord.data.sources

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.permits
import com.music.bitchord.data.settings.AudioQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.util.UUID

/**
 * One configured source: which protocol, which index.
 *
 * Persisted through [SourceRegistry], so an instance survives process death
 * with the settings that built it.
 */
@Serializable
data class SourceConfig(
    val id: String = UUID.randomUUID().toString(),
    val kind: SourceKind,
    /** What the user called it. Blank falls back to the server's host, or the kind's own label. */
    val label: String = "",
    val baseUrl: String = "",
    val enabled: Boolean = true,
) {
    /** What the player shows. Never blank. */
    val displayName: String
        get() = label.ifBlank {
            baseUrl.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                ?: kind.label
        }

    /** Whether this has enough filled in to be worth contacting at all. */
    val isComplete: Boolean
        get() = !kind.needsServer || baseUrl.isNotBlank()
}

/**
 * The user's sources, always tried in the fixed order [SourceKind] declares.
 *
 * [SourceKind.YOUTUBE] is seeded on first run and cannot be deleted, only
 * disabled — it needs no configuration, so a "remove" would delete something
 * the user could not then re-create by typing anything in, it would just be a
 * switch that hides itself. It is the only kind this build offers: the
 * third-party plugin system (addons, module indexes, JioSaavn) was removed
 * with the fork, and the registry keeps its list shape so a source kind can be
 * added back without a schema migration.
 */
object SourceRegistry {

    private const val TAG = "BitChord"

    private lateinit var prefs: SharedPreferences

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Every configured source, enabled or not. */
    val configs = MutableStateFlow<List<SourceConfig>>(emptyList())

    /**
     * Built instances, keyed by config id, rebuilt whenever [configs] changes.
     *
     * Held rather than constructed per call so that a source with any warmed
     * state keeps it across tracks instead of re-probing on every resolve.
     */
    private var instances: Map<String, MusicSource> = emptyMap()

    fun init(context: Context) {
        // Plain prefs: the only kind left (YouTube) stores no credentials or
        // server URLs worth encrypting, and the encrypted-prefs helper went
        // with the auth features this fork dropped.
        prefs = context.getSharedPreferences("bitchord_sources", Context.MODE_PRIVATE)

        val stored = prefs.getString(KEY_SOURCES, null)?.let(::decodeStored) ?: emptyList()
        val after = sourcesForInit(stored)

        publish(after, persist = false)
        if (after != stored) {
            prefs.edit()
                .putString(KEY_SOURCES, json.encodeToString(ListSerializer(SourceConfig.serializer()), after))
                .apply()
        }
    }

    /**
     * Built-in seeding, kept pure for tests.
     *
     * YouTube is force-enabled even where stored state says off: a config
     * written by an older build that disabled it has no screen left to turn it
     * back on, and a disabled YouTube means a silent player.
     */
    internal fun sourcesForInit(stored: List<SourceConfig>): List<SourceConfig> {
        // Seeded rather than persisted-on-first-write, so a build adding a new
        // built-in kind picks it up for existing installs too. SourceConfig's
        // default is the policy: YouTube on.
        val seeded = stored + BUILT_IN_KINDS
            .filter { kind -> stored.none { it.kind == kind } }
            .map { SourceConfig(kind = it) }

        // Kinds this build no longer carries — the removed plugin sources —
        // are dropped rather than kept disabled, since nothing can rebuild or
        // serve them.
        return seeded
            .filter { it.kind in BUILT_IN_KINDS }
            .map { config ->
                if (config.kind == SourceKind.YOUTUBE && !config.enabled) config.copy(enabled = true) else config
            }
    }

    /**
     * Decodes a stored source list one entry at a time rather than as a
     * single list, so one entry naming a kind this build no longer has —
     * left over from before a kind was retired — doesn't take every other
     * entry down with it. A strict `List<SourceConfig>` decode fails whole:
     * one bad enum value and the user's real, working config is silently
     * gone along with it.
     */
    private fun decodeStored(raw: String): List<SourceConfig> {
        val elements = runCatching { json.parseToJsonElement(raw).jsonArray }
            .getOrElse { return emptyList() }
        return elements.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(SourceConfig.serializer(), element) }
                .onFailure { TrackLog.w(TAG, "dropping unreadable stored source: ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * The enabled sources, in the order [SourceKind] declares.
     *
     * The user's standing choice and nothing else. Playback starts here; a
     * stream is budgeted further by [activeForPlayback].
     */
    fun active(): List<MusicSource> =
        enabledConfigs(configs.value)
            .sortedBy { it.kind.rank }
            .mapNotNull { instances[it.id] }

    /** The eligibility gate used by the playback source walk. */
    internal fun enabledConfigs(configs: List<SourceConfig>): List<SourceConfig> =
        configs.filter { it.enabled && it.isComplete }

    /**
     * [active], minus the sources the ceiling on the connection in hand does
     * not pay for — see [AudioQuality.permits].
     *
     * Every path that starts or plans a *stream* asks this rather than
     * [active], so the Wi-Fi and mobile-data rungs stay two independent
     * answers to "what does this minute cost" and switching networks switches
     * between them.
     */
    fun activeForPlayback(): List<MusicSource> {
        val ceiling = AppSettings.effectiveAudioQuality
        return active().filter { ceiling.permits(it.kind) }
    }

    /**
     * Whether a stream [configId] served may still be played under the ceiling
     * in force — the same test as [activeForPlayback], asked of a stream that
     * was found earlier, under whatever ceiling applied then.
     *
     * Null or unknown is a yes: a stream that names no source is YouTube's own,
     * and YouTube is permitted on every rung.
     */
    fun permitsForPlayback(
        configId: String?,
        ceiling: AudioQuality = AppSettings.effectiveAudioQuality,
    ): Boolean {
        val kind = configId?.let { config(it)?.kind } ?: return true
        return ceiling.permits(kind)
    }

    fun instance(configId: String): MusicSource? = instances[configId]

    fun config(configId: String): SourceConfig? = configs.value.firstOrNull { it.id == configId }

    // ── Editing ─────────────────────────────────────────────────────────

    fun add(config: SourceConfig) = publish(configs.value + config)

    fun update(config: SourceConfig) =
        publish(configs.value.map { if (it.id == config.id) config else it })

    fun remove(configId: String) {
        val target = config(configId) ?: return
        if (target.kind in BUILT_IN_KINDS) return
        publish(configs.value.filterNot { it.id == configId })
    }

    /**
     * Turns one source on or off.
     *
     * YouTube is not switchable and silently ignores a request to disable it.
     * It is the only source that can supply a home feed, radio or related
     * tracks, and nothing else holds the full catalogue — switching it off
     * doesn't even stop it being played. A switch that cannot honour its own
     * off position is worse than no switch, so it isn't offered one.
     */
    fun setEnabled(configId: String, enabled: Boolean) {
        if (!enabled && config(configId)?.kind == SourceKind.YOUTUBE) return
        publish(configs.value.map { if (it.id == configId) it.copy(enabled = enabled) else it })
    }

    private fun publish(next: List<SourceConfig>, persist: Boolean = true) {
        configs.value = next
        // Rebuilt against the previous map so that an untouched source keeps
        // the instance it already had, rather than being replaced by an
        // identical-but-cold one every time an unrelated row is toggled.
        val previous = instances
        instances = next.associate { config ->
            val existing = previous[config.id]?.takeIf { it.configuredBy(config) }
            config.id to (existing ?: build(config))
        }
        if (persist && ::prefs.isInitialized) {
            prefs.edit()
                .putString(KEY_SOURCES, json.encodeToString(ListSerializer(SourceConfig.serializer()), next))
                .apply()
        }
    }

    private fun build(config: SourceConfig): MusicSource = when (config.kind) {
        SourceKind.YOUTUBE -> YouTubeSource(config)
    }

    /**
     * Whether an already-built instance still matches its stored config —
     * false after an edit that changes where it points, which is exactly when
     * the warm instance must be thrown away.
     */
    private fun MusicSource.configuredBy(config: SourceConfig): Boolean =
        this is ConfigBacked && this.config == config

    /** Implemented by sources that carry their [SourceConfig], so [publish] can tell a real edit from a no-op. */
    internal interface ConfigBacked {
        val config: SourceConfig
    }

    // ── Track identity ──────────────────────────────────────────────────

    /**
     * A source-backed track's id, as it travels through the queue.
     *
     * Packed into the existing [Song.videoId][com.music.bitchord.data.model.Song.videoId]
     * rather than added beside it: that field is the app's media id everywhere —
     * the queue, the notification, the history, the like state — and a second
     * identity field would have to be threaded through every one of them, with
     * each place that forgot silently falling back to treating the track as
     * YouTube's.
     */
    fun trackKey(configId: String, trackId: String) = "$PREFIX$configId$SEPARATOR$trackId"

    /** The `(configId, trackId)` inside a [trackKey], or null if this is an ordinary YouTube id. */
    fun parseTrackKey(key: String): Pair<String, String>? = SourceTrackKeys.parse(key)

    /** The playback URI for a source-backed track; [PlaybackService] resolves it at open time. */
    fun trackUri(configId: String, trackId: String): String =
        Uri.Builder()
            .scheme("bitchord")
            .authority("source")
            .appendQueryParameter("s", configId)
            .appendQueryParameter("t", trackId)
            .build()
            .toString()

    private val BUILT_IN_KINDS = listOf(SourceKind.YOUTUBE)

    private const val KEY_SOURCES = "sources"
    private const val PREFIX = "src:"
    private const val SEPARATOR = "::"
}
