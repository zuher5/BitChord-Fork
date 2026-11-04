package com.music.bitchord.data.sources

import android.net.Uri
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a queued track into an openable stream, using whichever source can
 * best serve it.
 *
 * Two things happen here that don't happen in any single [MusicSource]:
 *
 *  1. **The quality question is answered once**, from the connection in hand
 *     and the user's ceiling for it — see [requestForNow]. Sources are told
 *     what to serve; they don't each re-derive it.
 *
 *  2. **The order is applied.** A track is pinned to the source that produced
 *     it, but a pin is a starting point, not a cage: with lossless on, a
 *     higher-priority source that can serve the same recording bit-exact gets
 *     asked first, and a source that fails gets stepped over rather than
 *     failing the track.
 *
 * Whether another source *has* the same recording is [TrackMatcher]'s question,
 * not this one's. Everything here does with a candidate list is ask that, and
 * everything a source is asked for comes from the same place — so the library,
 * a playlist, radio, search and the home feed all substitute on identical
 * terms, whichever of them a track was queued from.
 */
object SourceResolver {

    private const val TAG = "BitChord"

    /**
     * The quality-tier tags a search row's [Song.sourceQuality] can carry,
     * inherited from the removed module/addon protocol. Rows from a source
     * that no longer exists may still sit in saved playlists and history, so
     * [preferred] keeps honouring them when ranking candidates.
     */
    private const val LOSSLESS = "LOSSLESS"
    private const val DOLBY = "DOLBY"

    /**
     * What to ask a source for, right now, from the connection's ceiling alone
     * — see [AppSettings.effectiveAudioQuality].
     *
     * This only decides the *tier* a source is asked for. Which sources get
     * asked at all is [SourceRegistry.activeForPlayback]'s question, answered
     * off the same ceiling.
     */
    fun requestForNow(): StreamRequest {
        val ceiling = AppSettings.effectiveAudioQuality
        // Always the best the sources can do, bounded only by the connection's
        // own ceiling. There used to be a "Prefer lossless" switch in front of
        // this and it earned its removal: every source already degrades on its
        // own terms, so switching it off could only ever ask for a *worse*
        // file than the source was holding.
        return when {
            ceiling == AudioQuality.LOSSLESS -> StreamRequest.Lossless
            ceiling.maxKbps == Int.MAX_VALUE -> StreamRequest.Best
            else -> StreamRequest.Capped(ceiling.maxKbps)
        }
    }

    /**
     * @param uri a `bitchord://source?...` URI as built by [SourceRegistry.trackUri].
     * @return the stream, or null when nothing enabled could serve the track.
     */
    suspend fun resolve(uri: Uri): SourceStream? {
        val configId = uri.getQueryParameter("s") ?: return null
        val trackId = uri.getQueryParameter("t") ?: return null
        return resolve(
            configId = configId,
            trackId = trackId,
            target = targetIn(uri),
            pickedMix = uri.getQueryParameter(MIX_PARAM),
        )
    }

    /**
     * `&mix=` on a legacy source row's playback URI: which mix the row itself
     * was, [MIX_ATMOS] or [MIX_STEREO] as its search answer tagged it, or
     * [MIX_ANY] when it said neither.
     *
     * Only rows picked from the removed addon search ever carried this
     * marker; it is kept so URIs already recorded in history and playlists
     * keep parsing instead of failing on an unknown query. Nothing emits it
     * any more. Absent, nothing changes.
     */
    const val MIX_PARAM = "mix"
    const val MIX_STEREO = "stereo"
    const val MIX_ATMOS = "atmos"
    const val MIX_ANY = "any"

    /** The [MIX_PARAM] value for a row whose search answer tagged it [sourceQuality]. */
    fun mixOf(sourceQuality: String?): String = when (sourceQuality) {
        null -> MIX_ANY
        DOLBY -> MIX_ATMOS
        else -> MIX_STEREO
    }

    /**
     * The recording a playback URI describes, for matching it elsewhere.
     *
     * Title, artist and runtime ride in the URI because they are what a
     * cross-source match is made on, and the resolver runs on ExoPlayer's
     * loader thread with nothing but a DataSpec in hand — see
     * [toMediaItem][com.music.bitchord.playback.toMediaItem].
     */
    fun targetIn(uri: Uri) = TrackMatcher.Target(
        title = uri.getQueryParameter("n").orEmpty(),
        artist = uri.getQueryParameter("a").orEmpty(),
        durationSec = uri.getQueryParameter("d")?.toIntOrNull(),
        album = uri.getQueryParameter("l"),
        isExplicit = uri.getQueryParameter("e")?.let { it == "1" },
        isVideo = uri.getQueryParameter("m") == "1",
    )

    /**
     * @param target is what a cross-source match is made on. Without it the
     *   only possible behaviour is "the pinned source or nothing", which is
     *   still a correct outcome — just a worse one.
     * @param pickedMix a legacy `&mix=` marker (see [MIX_PARAM]) on rows
     *   picked by hand from the removed addon search. It is honoured in one
     *   direction only: such a row is not substituted with YouTube's own copy,
     *   which is a different recording of the song the listener chose.
     */
    suspend fun resolve(
        configId: String,
        trackId: String,
        target: TrackMatcher.Target,
        pickedMix: String? = null,
    ): SourceStream? {
        val request = requestForNow()
        val active = SourceRegistry.activeForPlayback()
        // A pin identifies where the row originally came from; it does not
        // override the source switch. Looking the instance up directly used to
        // reopen disabled tracks already sitting in the queue. Resolve the pin
        // only from the enabled list so Off means off for both newly matched
        // tracks and source-backed rows queued earlier.
        val pinned = active.firstOrNull { it.configId == configId }

        // The upgrade path: with lossless asked for and the pinned source
        // unable to serve it, anything ranked above it that can is worth
        // asking first. This is the whole reason the list is ordered — it is
        // what makes "my own FLAC of this, if I have one, else stream it"
        // expressible.
        // A row picked by hand is not up for substitution — see [MIX_PARAM].
        if (pickedMix == null && request is StreamRequest.Lossless && pinned?.kind?.canServeLossless != true) {
            for (source in rankedAbove(configId, active)) {
                if (!source.kind.canServeLossless) continue
                val upgraded = matchAndStream(source, target, request) ?: continue
                // Only a genuinely lossless answer is an upgrade. A source that
                // *can* serve lossless but settled for a transcode of this
                // particular track has not beaten the pinned source at anything
                // — and returning its settle-for here jumped ahead of the
                // track's own source, which is the one the user picked and may
                // well hold something better. It falls through to the pinned
                // source instead, and to [bestAcross] below if that fails.
                if (upgraded.format.isLossless != true) continue
                TrackLog.d(TAG, "lossless upgrade: '${target.title}' served by ${source.displayName}")
                return upgraded
            }
        }

        if (pinned != null) {
            attempt(pinned) { pinned.stream(trackId, request) }
                ?.let { return it.copy(sourceConfigId = pinned.configId) }
        }

        // Last resort. A track whose own source is down is still a track the
        // user asked for, and another source having it is not unlikely — this
        // is the difference between a dead server skipping the queue forward
        // and a dead server being invisible.
        //
        // Not YouTube, though, for a row picked by hand under the removed
        // addon system: its Opus is a lesser copy of a different upload, and
        // the listener chose that row precisely to not be hearing it — see
        // [MIX_PARAM].
        val fallbacks = active.filterNot {
            it.configId == configId || (pickedMix != null && it.kind == SourceKind.YOUTUBE)
        }
        val (fallbackSource, stream) = bestAcross(fallbacks, target, request) ?: return null
        TrackLog.d(TAG, "fallback: '${target.title}' served by ${fallbackSource.displayName}")
        return stream
    }

    /**
     * The stream for a YouTube track from a source the user ranked above
     * YouTube, or null when none of them has the recording.
     *
     * A YouTube track keeps its bare video id rather than a
     * [SourceRegistry.trackKey] — see [YouTubeSource] for why — so it reaches
     * playback as `bitchord://watch?v=…` and never passes through [resolve].
     * Without this, ordering a source above YouTube did nothing for anything
     * *queued* from YouTube: the library, a playlist, radio, the home feed —
     * which is very nearly everything. The list said "prefer my server" and
     * only search results honoured it.
     *
     * The match is the same strict one [resolve] uses, for the same reason:
     * this substitutes something else for the track the user picked, and a
     * loose match plays the wrong song under the right title.
     *
     * Every ranked source is asked at once and the first playable answer is
     * taken — see [bestAcross]. This is the latency-critical half of the pair:
     * it is racing YouTube's own walk, and a stream that arrives after that race
     * is lost cannot start a track. The unhurried half is [upgradeFor], which
     * runs with sound already playing and is where a slow source's better answer
     * gets its hearing.
     */
    suspend fun substituteForYouTube(target: TrackMatcher.Target): SourceStream? {
        // Video rows always use YouTube's own stream unless the listener
        // explicitly converts the current item in the player. A cross-source
        // match can be another recording and a wrong song altogether.
        if (target.title.isBlank() || target.isVideo) return null
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        val request = requestForNow()
        val (source, stream) = bestAcross(rankedAbove(youtube.configId, active), target, request)
            ?: return null
        // Says what was found, not what the caller will do with it. This
        // line used to read "substituted" unconditionally, including for
        // streams the caller went on to refuse — which made a log of a
        // track that played on YouTube look like a track that hadn't.
        TrackLog.d(
            TAG,
            "substituted: '${target.title}' served by ${source.displayName} over YouTube" +
                " at ${stream.format.summary}" + if (stream.belowRequest) " (below request)" else "",
        )
        return stream
    }

    /**
     * The addons Direct Source Stream plays from — always empty now.
     *
     * The addon system was removed with this fork, so no source can ever sit
     * ranked above YouTube with a direct catalogue of its own. The signature
     * is kept so [com.music.bitchord.playback.DirectSourceGate] and the cache
     * path built on it compile unchanged; callers gate on this being empty and
     * fall through to YouTube.
     */
    fun directAddons(): List<MusicSource> = emptyList()

    /**
     * The addon copy of a YouTube-queued track, for Direct Source Stream —
     * always null now that the addon system is removed; see [directAddons].
     */
    suspend fun directStream(target: TrackMatcher.Target): SourceStream? = null

    /**
     * The reliable last rung for a source-backed queue item whose chosen stream
     * failed in the player.
     *
     * Unlike [resolve], this asks only YouTube. The failed source must not get
     * another chance to return the identical URL, and unlike
     * [substituteForYouTube] this starts with metadata rather than an existing
     * YouTube video id, so it first finds the matching YouTube Music row.
     */
    suspend fun youtubeFallback(target: TrackMatcher.Target): SourceStream? {
        if (target.title.isBlank() || target.isVideo) return null
        val youtube = SourceRegistry.activeForPlayback()
            .firstOrNull { it.kind == SourceKind.YOUTUBE }
            ?: return null
        return matchAndStream(
            source = youtube,
            target = target,
            request = StreamRequest.Best,
            strictLength = target.durationSec != null,
            requireSharedArtist = true,
        )?.also {
            TrackLog.d(TAG, "YouTube fallback matched '${target.title}' after its higher-quality source failed")
        }
    }

    /**
     * The copy of [target] held by a source quick enough to ask about *before*
     * the track is played — always null now.
     *
     * This used to warm the quick third-party sources ahead of playback; the
     * plugin system that supplied them was removed with this fork, and
     * YouTube's own read-ahead in
     * [AudioCache][com.music.bitchord.playback.AudioCache] needs no source
     * walk. The signature is kept so the read-ahead caller compiles unchanged;
     * it already treats null as "fall back to YouTube".
     */
    suspend fun prefetchSubstitute(target: TrackMatcher.Target): SourceStream? = null

    /**
     * A stream that genuinely satisfies the current request, for a track that
     * is already playing on one that doesn't — or null if there isn't one.
     *
     * The same search as [substituteForYouTube] with two differences, both of
     * which are only affordable because sound is already coming out:
     *
     *  - Every backend *within* a source is waited for, including the one the
     *    live path gave up on to get playback started (`waitForAll`). That
     *    backend is frequently the point: dropping it is what left the listener
     *    on a stream from whoever happened to be quick.
     *  - A result that isn't lossless is still worth having when it is
     *    audibly better than what is playing — see [worthSwapping]. Refusing
     *    those outright is what left a track on YouTube's 160kbps Opus while
     *    a 320kbps AAC sat in hand, unused, because it wasn't the FLAC that
     *    had been asked for.
     *  - Every source is asked, not only the ones that can serve lossless. That
     *    follows from the bullet above: once a lossy stream can win, a lossy
     *    *source* has to be allowed to offer one.
     *
     * Where it matches [substituteForYouTube] exactly is in taking the first
     * answer that clears the bar rather than the best of all of them — see
     * [bestAcross]. The bar here is [worthSwapping] rather than "satisfies the
     * request", and it is a high one: anything clearing it is lossless, or a
     * gain of [UPGRADE_MIN_GAIN_KBPS] over what the listener is hearing. Holding
     * such a stream back to see whether a slower source can do better trades a
     * certain improvement now for a possible improvement later, and "later" here
     * was measured at thirteen seconds.
     *
     * The cost of that choice is real and worth naming: a slow source holding a
     * FLAC can lose to a fast one holding a 320kbps AAC, and the track then
     * plays lossy for the rest of its length, because [QualityUpgrade][com.music.bitchord.playback.QualityUpgrade]
     * marks a track asked once the answer is yes. It is the same trade the live
     * path makes, made for the same reason.
     *
     * [target] must carry the runtime of the track *actually playing* — see
     * [matchAndStream]'s use of it. Swapping the audio under a listener is
     * only defensible when the replacement is the same recording, and length
     * is the check that a title cannot fake.
     *
     * @param playing what the listener is hearing now, so a lossy candidate
     *   can be judged against it rather than against the request. Null means
     *   unknown, and an unknown floor is treated as one nothing lossy clears:
     *   a swap that might be a downgrade is worse than no swap at all.
     * @param servedBy the [MusicSource.configId] already serving the track, if
     *   known. Asking it again is wasted: the same source, asked the same
     *   query at the same tier, is deterministic and can only reproduce the
     *   stream already playing — which [worthSwapping] would reject anyway,
     *   but not before spending a search and a stream call on it. See the
     *   'isn't worth swapping ... off' log line this was written to stop.
     */
    suspend fun upgradeFor(
        target: TrackMatcher.Target,
        playing: StreamFormat? = null,
        servedBy: String? = null,
    ): SourceStream? {
        if (target.title.isBlank() || target.durationSec == null || target.isVideo) return null
        val request = requestForNow()
        val active = SourceRegistry.activeForPlayback()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        // Every source gets asked, whether or not it can serve lossless, and
        // they are asked at once.
        //
        // There used to be a `canServeLossless` skip here, applied whenever the
        // request was [StreamRequest.Lossless] — which is what an unmetered
        // connection asks for, i.e. nearly always. Its reasoning was that only a
        // lossless source can satisfy a lossless request, and that is true and
        // beside the point: this function is not serving the request, it is
        // deciding whether anything beats what is *already playing*.
        // [worthSwapping] is the bar for that, and a 320kbps source clears it
        // over YouTube's 160kbps Opus by nearly twice the required margin.
        //
        // Racing them matters as much as asking them. Walked in rank order, a
        // slow first source spent thirteen seconds of a 143-second track played
        // at the wrong bitrate, and the seam then landed mid-song rather than
        // near its start. Raced, the same swap happens inside a second.
        val (source, chosen) = bestAcross(
            rankedAbove(youtube.configId, active).filterNot { it.configId == servedBy },
            target,
            request,
            waitForAll = true,
            strictLength = true,
            requireSharedArtist = true,
            driftSec = upgradeSlackSec(),
        ) { candidate, stream ->
            worthSwapping(stream.format, playing).also { worth ->
                // Named rather than skipped silently. This is the one refusal
                // in the upgrade path that discards a stream already found,
                // matched and length-checked, and a silent skip reads in the log
                // exactly like a source having nothing — which is how a null
                // [playing] came to quietly turn the whole cached-track path
                // lossless-only for a while without leaving a trace.
                if (!worth) {
                    TrackLog.d(
                        TAG,
                        "${candidate.displayName}'s ${stream.format.summary} isn't worth swapping " +
                            "'${target.title}' off ${playing?.summary ?: "an unmeasured stream"}",
                    )
                }
            }
        } ?: return null
        TrackLog.d(TAG, "upgrade found: '${target.title}' at ${chosen.format.summary} from ${source.displayName}")
        return chosen
    }

    /**
     * Whether [candidate] is enough better than [playing] to be worth the
     * break in the audio that swapping to it costs.
     *
     * Lossless always is: it is what was asked for, and the whole point.
     *
     * A lossy candidate has to clear [UPGRADE_MIN_GAIN_KBPS] over what is
     * already playing, which is deliberately a wide gap rather than a strict
     * improvement. Bitrate compares poorly across codecs — Opus at 160kbps
     * and AAC at 256kbps are much the same thing to listen to — so a margin
     * narrow enough to be codec-sensitive would be a margin that buys a seam
     * in the audio for nothing. 160 to 320 clears it; 128 to 192 does not.
     */
    internal fun worthSwapping(candidate: StreamFormat, playing: StreamFormat?): Boolean {
        // Never away from an immersive mix. A FLAC is a better *copy* and a
        // worse *mix* once Atmos is what the listener chose, and cutting one in
        // over the other mid-song is a downgrade dressed as an upgrade.
        // [QualityUpgrade.needsLosslessFollowUp] already treats Atmos as final,
        // so this is the belt to that braces — it also covers the late answer
        // that arrives from a pass started before the Atmos stream landed.
        if (playing?.isDolbyAtmos == true && !candidate.isDolbyAtmos) return false
        if (candidate.isLossless == true || candidate.isDolbyAtmos) return true
        val gain = (candidate.kbps ?: return false) - (playing?.kbps ?: return false)
        return gain >= UPGRADE_MIN_GAIN_KBPS
    }

    /**
     * Whether two runtimes are close enough to be the same recording, for a
     * swap into a track that is already playing.
     *
     * The same [UPGRADE_DRIFT_SEC] bar [matchAndStream] applies to the
     * candidates it finds itself, exposed for the one candidate it doesn't:
     * the live lookup [QualityUpgrade][com.music.bitchord.playback.QualityUpgrade]
     * inherits when the fallback wins the race.
     *
     * Either side being unknown is a no. An unverifiable length is not a
     * length that agrees, and the cost of being wrong here is a listener's
     * song replaced mid-play by a different cut of it.
     *
     * @param slackSec the listener's own tolerance when it is a swap being
     *   decided — see [upgradeSlackSec].
     */
    fun sameRecordingAs(candidateSec: Int?, playingSec: Int?, slackSec: Int = UPGRADE_DRIFT_SEC): Boolean {
        if (candidateSec == null || playingSec == null) return false
        return kotlin.math.abs(candidateSec - playingSec) <= slackSec
    }

    /**
     * How far a replacement's runtime may sit from the playing track's for a
     * mid-song swap: Settings' "Track length tolerance".
     *
     * The setting used to reach only the audition and the check after the
     * swap, both of which run on a stream already chosen. Choosing it ran on
     * [UPGRADE_DRIFT_SEC] alone, so every candidate between two seconds and
     * the setting was thrown out before the setting was ever consulted — the
     * slider could tighten the bar and never loosen it. Measured on 'Take on
     * Me': a 24-bit/192 kHz remaster at 3:48 against YouTube's 3:45, refused
     * three seconds out under a tolerance set to five, and the upgrade landed
     * on the 16-bit album cut instead.
     */
    fun upgradeSlackSec(): Int = AppSettings.upgradeLengthSlackSeconds.value

    /**
     * Whether anything outranks YouTube right now — i.e. whether a YouTube
     * track is worth offering around before it is resolved.
     *
     * Answerable from the source list alone, without a search, which is what
     * lets the cache and the read-ahead in
     * [AudioCache][com.music.bitchord.playback.AudioCache] decide how to treat
     * a YouTube id before anyone has asked a source for it.
     */
    fun canSubstituteForYouTube(): Boolean =
        SourceRegistry.activeForPlayback().indexOfFirst { it.kind == SourceKind.YOUTUBE } > 0

    /**
     * The sources ranked above [configId], in order.
     *
     * A config that isn't in [active] ranks last: it is disabled or incomplete,
     * and everything that *is* enabled is worth trying ahead of it.
     */
    private fun rankedAbove(configId: String, active: List<MusicSource>): List<MusicSource> =
        active.indexOfFirst { it.configId == configId }
            .let { if (it < 0) active.size else it }
            .let { active.take(it) }

    /**
     * The best stream the configured [sources] can serve for [target] — **all
     * of them asked at once** — or null if none of them has the recording.
     *
     * ### Why they race rather than queue
     *
     * Asking them in rank order is the obvious reading of an ordered list, and
     * it is wrong whenever the sources differ in speed by orders of magnitude —
     * as the removed plugin catalogues did against YouTube's own walk. Queued
     * behind a slow source, a fast answer arrived far too late for a song about
     * to start, so
     * [PlaybackService][com.music.bitchord.playback.PlaybackService]'s resolve
     * won the race every single time and the listener got YouTube's Opus while
     * a better copy sat fractions of a second away. Raced, the fast answer
     * arrives before YouTube's own walk finishes and the track starts on it.
     *
     * ### What rank still decides, and what it no longer does
     *
     * Rank decides who is *asked* — [rankedAbove] is still what builds this list
     * — and it breaks ties between answers that arrive together, since each
     * sweep folds in everything that has already crossed the line and picks the
     * best of them with [isBetter]. What it no longer does is let a slow
     * favourite hold up a fast alternative.
     *
     * **A slow source is not thereby lost.** Whatever is returned here starts
     * playing; if it is [SourceStream.belowRequest] the track is marked for a
     * second look, and [upgradeFor] then asks *every* source again with no time
     * limit and swaps up only if what comes back genuinely beats what is playing
     * — see [worthSwapping]. So a source that needed thirteen seconds to find a
     * FLAC still gets to serve it, mid-track, and one that needed thirteen
     * seconds to find a 128kbps MP3 is correctly ignored. That is the trade this
     * whole path exists to make: sound now, quality shortly after.
     *
     * On the latency-critical path, sources still running when an answer is
     * taken are cancelled — the second look re-asks them properly, and leaving
     * them running would spend a listener's radio on a result nothing is waiting
     * for. The background-upgrade path passes [waitForAll], however: it already
     * chose to wait for every source's patient search window, so it must also
     * collect the resulting streams before choosing. Previously that flag only
     * reached each source's search call; this loop still returned the first
     * usable stream and cancelled the rest, which could discard an exact lossless
     * result during an upgrade.
     *
     * Two answers of equal quality go to the higher-ranked source, whenever
     * each arrived. Arrival order used to decide that, which handed every tie
     * to whoever happened to answer first rather than to the source the user
     * had ranked ahead.
     *
     * @param refine runs on each source's answer inside that source's own
     *   lookup, so its cost is paid in parallel. It may rewrite the stream
     *   (with what its bytes turned out to be) or refuse it with null.
     * @param timeoutMs ends the race at that point with whatever has already
     *   answered, rather than losing those answers along with the slow ones.
     * @param driftSec the [strictLength] bar — see [matchAndStream].
     * @return the winning source alongside its stream, so callers can name it in
     *   a log line without searching the list again.
     */
    internal suspend fun bestAcross(
        sources: List<MusicSource>,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
        driftSec: Int = UPGRADE_DRIFT_SEC,
        refine: suspend (MusicSource, SourceStream) -> SourceStream? = { _, stream -> stream },
        timeoutMs: Long? = null,
        accept: (MusicSource, SourceStream) -> Boolean = { _, _ -> true },
    ): Pair<MusicSource, SourceStream>? = coroutineScope {
        val until = timeoutMs?.let { System.nanoTime() + it * 1_000_000 }
        val rank = sources.withIndex().associate { (index, source) -> source to index }
        val running: MutableList<Deferred<Pair<MusicSource, SourceStream?>>> = sources
            .map { source ->
                async {
                    source to matchAndStream(
                        source,
                        target,
                        request,
                        waitForAll,
                        strictLength,
                        requireSharedArtist,
                        driftSec,
                    )?.let { refine(source, it) }
                }
            }
            .toMutableList()
        var best: Pair<MusicSource, SourceStream>? = null
        try {
            while (running.isNotEmpty()) {
                val awaiting = suspend {
                    select {
                        running.forEach { candidate -> candidate.onAwait { candidate } }
                    }
                }
                val first = if (until == null) {
                    awaiting()
                } else {
                    withTimeoutOrNull(remainingMs(until)) { awaiting() } ?: break
                }
                // Anything that crossed the line while that one was being waited
                // on is already sitting there. Folding those in costs no time at
                // all and is what lets rank break a tie between two sources that
                // both answered quickly.
                val ready = listOf(first) + running.filter { it !== first && it.isCompleted }
                running -= ready.toSet()
                for (done in ready) {
                    val (source, stream) = done.await()
                    if (stream == null) continue
                    if (!accept(source, stream)) continue
                    val held = best
                    val wins = held == null || isBetter(stream.format, held.second.format) ||
                        (!isBetter(held.second.format, stream.format) && rank.getValue(source) < rank.getValue(held.first))
                    if (wins) best = source to stream
                }
                // Playback needs the first usable answer so sound can start.
                // An upgrade is different: it runs while audio is already
                // playing, and its caller explicitly requested every source's
                // patient result. Let every source finish so a fast lower tier
                // cannot cancel a later, better rendition.
                if (best != null && !waitForAll) break
            }
        } finally {
            running.forEach { it.cancel() }
        }
        best
    }

    /**
     * Searches [source] for the recording in [target] and streams it if one of
     * the answers really is that recording — see [TrackMatcher].
     *
     * Each query the matcher offers is tried in turn, because the first one
     * failing is usually the catalogue disagreeing about how a track is
     * *filed*, not about whether it holds it. Stopping at the first empty
     * answer is what made a source look like it was missing half of what it
     * had. A source that *throws* still gets no second chance: that is its
     * server having a problem, and asking it again differently won't fix it.
     *
     * @param waitForAll holds a multi-backend search open for every backend
     *   instead of answering from whichever of them are quick — affordable only
     *   when nobody is waiting on the first note.
     * @param strictLength requires a candidate's runtime to agree with
     *   [target]'s to within [driftSec]. Kept apart from [waitForAll]
     *   because it is only meaningful when the target *has* a runtime:
     *   [TrackMatcher.withinSeconds] answers false for every candidate against a
     *   null one, so asking for this against a target that never carried a
     *   duration is not a strict search but an empty one.
     * @param driftSec [UPGRADE_DRIFT_SEC] unless the caller is deciding a
     *   mid-song swap, which answers to the listener's tolerance instead — see
     *   [upgradeSlackSec]. A download and the YouTube fallback keep the fixed
     *   bar: neither cuts into audio already playing, which is what the setting
     *   is about.
     */
    private suspend fun matchAndStream(
        source: MusicSource,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        requireSharedArtist: Boolean = false,
        driftSec: Int = UPGRADE_DRIFT_SEC,
    ): SourceStream? {
        for (query in TrackMatcher.queries(target)) {
            val candidates = attempt(source) {
                source.search(
                    query,
                    limit = MATCH_CANDIDATES,
                    waitForAll = waitForAll,
                    // The search and the stream are one question here: a
                    // catalogue that files its rows by tier should describe
                    // them at the tier this is about to ask for.
                    request = request,
                )
            } ?: return null
            var matches = TrackMatcher.ranked(candidates, target)
            if (requireSharedArtist) {
                matches = matches.filter { TrackMatcher.sharesArtist(target.artist, it.artist) }
            }
            // The extra bar for standing in for one specific recording: the
            // replacement has to be the same *length*, to the second or so. A
            // title and an artist can agree across two different edits of a
            // song; a runtime that agrees this closely is one recording, and
            // nothing else is worth cutting a listener's audio for — or filing
            // on their device under the name of the track they asked for.
            if (strictLength) {
                val (sameLength, otherLength) = matches.partition {
                    TrackMatcher.withinSeconds(it, target, driftSec)
                }
                // Named rather than dropped silently: a copy refused for its
                // length reads in the log exactly like a source that never had
                // it, and the two have opposite fixes.
                if (otherLength.isNotEmpty()) {
                    TrackLog.d(
                        TAG,
                        "${source.displayName}: ${otherLength.size} match(es) for '${target.title}' outside " +
                            "±${driftSec}s of ${target.durationSec}s: " +
                            otherLength.joinToString { "'${it.title}' ${it.durationText ?: "?"}" },
                    )
                }
                matches = sameLength
            }
            if (matches.isEmpty()) continue
            return streamBest(
                source,
                matches,
                target,
                request,
                // The listener's tolerance already decided which runtimes are
                // the same recording here; narrowing again to a fixed three
                // seconds would quietly undo a setting of five.
                sameLengthSec = if (strictLength) maxOf(SAME_RECORDING_SEC, driftSec) else SAME_RECORDING_SEC,
                // Only the patient lookups can afford to open more than one row.
                compareRows = waitForAll,
            )
        }
        return null
    }

    /**
     * Opens the best of [matches] that can actually serve [request].
     *
     * Two things happen here that a single "take the top match" cannot:
     *
     *  1. **Rows that advertise the tier asked for go first.** Every one of
     *     these is genuinely the recording, so which one plays is a question
     *     about quality, not identity — and a catalogue that has already said
     *     it holds a FLAC is a better place to ask for one than a catalogue
     *     that said nothing. Without this the order was confidence alone, and
     *     a 16-bit FLAC lost to a Deezer row over how its artists were spelt.
     *
     *  2. **What comes back is checked against what was asked for.** A source
     *     that cannot serve lossless does not always say so; some quietly walk
     *     their own fallback chain and hand back a 128kbps MP3 with the right
     *     title on it. Reading [StreamFormat] before accepting the URL is what
     *     turns that into "this one can't, try the next" instead of into the
     *     listener's evening.
     *
     * The under-quality stream is kept rather than dropped: if nothing better
     * exists anywhere, playing the MP3 is still better than skipping the
     * track. It is a floor, not a first choice.
     */
    /**
     * The matching rows, in the order they are worth opening.
     *
     * Two rules, and the order of them is the point:
     *
     *  1. **Length decides which recording, first.** When any candidate agrees
     *     with the runtime being asked for to within a couple of seconds, only
     *     the candidates that agree are eligible at all. A catalogue holding
     *     the track under its right title and right artist can still be
     *     holding a different *cut* of it — a DJ edit on a compilation, an
     *     extended mix — and the runtime is what separates those when nothing
     *     in the title does. If nothing agrees, nothing is excluded: the
     *     runtimes are simply not informative here and the score stands alone.
     *
     *  2. **Quality decides between equals, second.** Among rows that are the
     *     same recording, one advertising a lossless copy is the better place
     *     to ask. This was doing that job *first*, which is how a 185-second
     *     "Punjabi Dj Holi songs" cut beat the 180-second album track on the
     *     strength of the word `flac` in its listing. A declared tier is a
     *     reason to prefer one copy of a recording over another; it is not a
     *     reason to play a different recording.
     */
    internal fun preferred(
        matches: List<Song>,
        target: TrackMatcher.Target,
        wantsLossless: Boolean,
        sameLengthSec: Int = SAME_RECORDING_SEC,
    ): List<Song> {
        val sameLength = matches.filter { TrackMatcher.withinSeconds(it, target, sameLengthSec) }
        val eligible = sameLength.ifEmpty { matches }
        // The immersive mix goes first when the listener asked for immersive
        // audio and the device can decode it. This is not a quality rung and
        // is not competing with one: a catalogue may publish the Atmos mix as
        // its own row rather than as an alternate rendition of the stereo one,
        // in which case no `?atmos=` hint on the stereo row can ever reach it
        // — picking the row *is* the only way to hear it. Ordering is enough;
        // [streamBest] stops at the first row that answers, and an Atmos
        // answer on a device that cannot play it was already refused upstream
        // by the player's codec check.
        if (atmosWanted()) {
            val immersiveFirst = eligible.sortedByDescending { it.sourceQuality == DOLBY }
            if (immersiveFirst.firstOrNull()?.sourceQuality == DOLBY) return immersiveFirst
        }
        if (!wantsLossless) return eligible
        // Stable, so the confidence order [TrackMatcher.ranked] produced
        // survives inside each tier. Hi-res ahead of CD quality: both are
        // [LOSSLESS], and with one tier for the two a catalogue
        // holding a 24-bit remaster and a 16-bit album cut of the same song
        // handed over whichever it happened to list first.
        return eligible.sortedByDescending {
            when {
                it.sourceQuality != LOSSLESS -> 0
                it.sourceHiRes -> 2
                else -> 1
            }
        }
    }

    /**
     * Whether an immersive mix is worth preferring right now — the same gates
     * the playback side enforces before handing a stream to the decoder, asked
     * in the one place that chooses between rows.
     */
    private fun atmosWanted(): Boolean = DeviceCodecs.atmosWanted

    /**
     * @param compareRows keeps opening the remaining lossless rows after one
     *   has answered, and returns the best of them by [isBetter], rather than
     *   the first. Only for a caller that is not holding up a first note: each
     *   row is another stream call. Row tags say lossless or not, and a hi-res
     *   hint only where the catalogue gave one — the stream's own format is
     *   the only place the bit depth and rate are certain.
     */
    private suspend fun streamBest(
        source: MusicSource,
        matches: List<Song>,
        target: TrackMatcher.Target,
        request: StreamRequest,
        sameLengthSec: Int = SAME_RECORDING_SEC,
        compareRows: Boolean = false,
    ): SourceStream? {
        val wantsLossless = request is StreamRequest.Lossless
        val ordered = preferred(matches, target, wantsLossless, sameLengthSec)
        var settleFor: SourceStream? = null
        var best: SourceStream? = null
        for (match in ordered.take(STREAM_ATTEMPTS)) {
            if (best != null) {
                // Past the first answer, only rows that could beat it: another
                // lossless one. An immersive answer is beaten by nothing.
                if (best.format.isDolbyAtmos || match.sourceQuality != LOSSLESS) break
            }
            val trackId = SourceRegistry.parseTrackKey(match.videoId)?.second ?: match.videoId
            val opened = attempt(source) { source.stream(trackId, request) } ?: continue
            // The row this URL came from knows how long the recording is; the
            // URL itself doesn't. Carried along so a caller swapping this into
            // a track already playing can check it — see [SourceStream.durationSec].
            val stream = opened.copy(
                durationSec = TrackMatcher.secondsOf(match.durationText),
                sourceConfigId = source.configId,
            )
            val served = stream.format
            if (!wantsLossless || served.isLossless == true || served.isDolbyAtmos || served.statesNothingLossy) {
                TrackLog.d(
                    TAG,
                    "${source.displayName} matched '${match.title}' by '${match.artist}' " +
                        "id='${match.videoId}' album='${match.albumName ?: "?"}' " +
                        "duration=${match.durationText ?: "?"} explicit=${match.isExplicit ?: "?"} " +
                        "→ ${served.summary}",
                )
                if (!compareRows) return stream
                if (best == null || isBetter(stream.format, best.format)) best = stream
                continue
            }
            TrackLog.d(
                TAG,
                "${source.displayName} offered ${served.summary} for '${match.title}' " +
                    "by '${match.artist}' id='${match.videoId}' album='${match.albumName ?: "?"}' " +
                    "duration=${match.durationText ?: "?"} explicit=${match.isExplicit ?: "?"}; looking further",
            )
            // The floor is the *best* of what was refused, not the first of
            // it. These arrive in match order, which has nothing to do with
            // quality: a 320kbps AAC and a 128kbps MP3 are both rejections,
            // and which one the listener ends up on if nothing better exists
            // should not come down to which catalogue happened to be asked
            // first.
            settleFor = betterOf(settleFor, stream.copy(belowRequest = true))
        }
        return best ?: settleFor
    }

    /**
     * Whether [candidate] is a better rendition than [current], by codec first
     * and bitrate second. A null [current] is beaten by anything.
     *
     * The one rule for ranking two copies of the same recording, kept in one
     * place because three different walks now need it: [streamBest] choosing
     * between rows inside a source, [bestAcross] choosing between sources, and
     * [upgradeFor] choosing what to cut into a track that is already playing.
     *
     * Note that this is *not* [worthSwapping]. This asks which of two streams is
     * better; that one asks whether the difference is worth a break in the
     * audio, which is a much higher bar and only meaningful mid-playback.
     */
    internal fun isBetter(candidate: StreamFormat, current: StreamFormat?): Boolean {
        if (current == null) return true
        // Immersive first, and ahead of lossless rather than behind it. This
        // used to sit *after* the lossless test, which made it unreachable in
        // the only comparison it exists for: an Atmos stream is E-AC-3 and so
        // answers `isLossless == false`, so a FLAC won on the line above and
        // the Atmos line below never ran. A track offered as both then played
        // as the FLAC no matter what the Atmos setting said — and worse, a
        // mid-playback upgrade would cut the FLAC in over an Atmos stream
        // already playing. Anything immersive that reaches here has already
        // cleared the player's codec check, so it is a mix this device and
        // this listener both want.
        if (candidate.isDolbyAtmos != current.isDolbyAtmos) return candidate.isDolbyAtmos
        if (candidate.isLossless != current.isLossless) return candidate.isLossless == true
        // Between two lossless copies, depth then rate. Bitrate is the wrong
        // measure for those: a FLAC's states how compressible the recording
        // was, and most lossless streams state none at all — so a 24/192 and a
        // 16/44.1 used to tie, and the tie went to whoever ranked first.
        if (candidate.isLossless == true) {
            val depth = (candidate.bitDepth ?: 0).compareTo(current.bitDepth ?: 0)
            if (depth != 0) return depth > 0
            val rate = (candidate.sampleRateHz ?: 0).compareTo(current.sampleRateHz ?: 0)
            if (rate != 0) return rate > 0
        }
        return (candidate.kbps ?: 0) > (current.kbps ?: 0)
    }

    /** The higher-quality of two streams — see [isBetter]. */
    private fun betterOf(current: SourceStream?, candidate: SourceStream): SourceStream =
        if (current == null || isBetter(candidate.format, current.format)) candidate else current

    /**
     * Whether a format has said nothing that rules lossless out.
     *
     * Unknown is not the same as lossy, and a source that reports neither a
     * codec nor a bitrate has not failed the request — it has declined to
     * describe it, and the decoder will say soon enough. A stated bitrate is
     * different: nothing states a bitrate for a FLAC.
     */
    private val StreamFormat.statesNothingLossy: Boolean
        get() = isLossless == null && kbps == null

    /**
     * Runs [block], turning any failure into null and a log line.
     *
     * Every call into a source is a call to somebody else's server, and a
     * source that throws must cost the *source* its turn, not the track its
     * playback. Cancellation is re-thrown: that is the caller giving up, and
     * swallowing it would keep walking sources for a track nobody is waiting
     * for any more.
     */
    /** Milliseconds left before [until], a [System.nanoTime] instant; never negative. */
    private fun remainingMs(until: Long): Long =
        ((until - System.nanoTime()) / 1_000_000).coerceAtLeast(0)

    private suspend fun <T> attempt(source: MusicSource, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "${source.displayName} failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /**
     * How many answers per query are worth weighing.
     *
     * Wider than it needs to be for a well-behaved catalogue, because
     * [TrackMatcher.best] scores the whole list rather than taking the first
     * acceptable row: a backend that ranks the karaoke version, three covers
     * and a sped-up edit above the album cut still has the album cut in here
     * somewhere, and the extra rows cost one response body, not one request.
     */
    private const val MATCH_CANDIDATES = 15

    /**
     * How many of the matching rows are worth actually opening.
     *
     * Each one is a round trip to a stream endpoint, so this is the budget for
     * "the first copy wasn't the quality asked for" — enough to get past a
     * source whose lossless backend is down, not enough to spend a listener's
     * patience walking a whole result list.
     */
    private const val STREAM_ATTEMPTS = 3

    /**
     * How far a stand-in's runtime may sit from the recording asked for, for
     * the strict matches that are not a mid-song swap — a download, the
     * YouTube fallback for a failed source. A swap answers to the listener's
     * own tolerance instead; see [upgradeSlackSec].
     *
     * Far tighter than [TrackMatcher]'s own tolerance, and deliberately: two
     * seconds allows for a service rounding a runtime differently and nothing
     * else.
     */
    private const val UPGRADE_DRIFT_SEC = 2

    /**
     * How close two runtimes have to be to be the same cut of a song.
     *
     * Wide enough for a catalogue rounding, or a second of lead-in trimmed
     * differently. Narrow enough to separate the album track from the DJ edit
     * sitting next to it in the same search results under the same name.
     */
    private const val SAME_RECORDING_SEC = 3

    /**
     * How many kbps a lossy stream has to gain before it earns a seam in the
     * audio — see [worthSwapping].
     *
     * Sized off the two rates this actually decides between: YouTube's Opus,
     * which lands around 160, and a 320kbps AAC from a better catalogue.
     * Anything much smaller would start firing on differences no one can hear.
     */
    private const val UPGRADE_MIN_GAIN_KBPS = 96
}
