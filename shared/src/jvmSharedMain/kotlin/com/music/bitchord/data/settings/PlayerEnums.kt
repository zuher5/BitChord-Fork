package com.music.bitchord.data.settings

/**
 * Stream bitrate ceiling on the YouTube fallback path — MEDIUM, HIGH and
 * LOSSLESS all mean "whatever the best available Opus format is" there; what
 * used to tell them apart was which third-party sources were allowed to answer
 * *before* YouTube got asked. That part is [permits]; with the plugin sources
 * removed, every rung resolves YouTube's own ladder, and the rungs read:
 *
 * - [MEDIUM] and [LOW] — YouTube's own Opus ladder, capped at [maxKbps].
 * - [HIGH] and [LOSSLESS] — YouTube's best available rendition.
 *
 * [hourly] is what the ceiling costs in data over an hour of listening, which
 * is the only part of this a user actually cares about on a metered plan.
 */
enum class AudioQuality(
    val maxKbps: Int,
    val label: String,
    val detail: String,
    val hourly: String,
) {
    LOW(64, "Low", "~64 kbps · smallest download", "29 MB/hr"),
    MEDIUM(Int.MAX_VALUE, "Medium", "Best available · ~171 kbps Opus", "77 MB/hr"),
    HIGH(Int.MAX_VALUE, "High", "Best available Opus, no cap", "144 MB/hr"),
    LOSSLESS(Int.MAX_VALUE, "Lossless", "Best available, bit-exact where offered", "300+ MB/hr"),
    ;
}

/** The surface that was last open inside the expanded player. */
enum class LastPlayerScreen {
    MAIN,
    LYRICS,
    QUEUE,
}
