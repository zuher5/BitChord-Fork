package com.music.bitchord.data.sources

/**
 * The kinds of source this build knows how to talk to.
 *
 * Fixed and small on purpose. The third-party plugin system — addon servers,
 * QuickJS module indexes and the built-in JioSaavn source — was removed with
 * this fork; YouTube Music is the only streaming source left, so [rank] has no
 * ordering left to express. The enum stays an enum rather than collapsing into
 * a constant so [SourceConfig]'s stored `kind` field and the quality ceiling
 * (`permits`) keep decoding old persisted state without a schema migration.
 *
 * What varies per *instance* is [SourceConfig].
 */
enum class SourceKind(
    val label: String,
    /** Whether an instance needs a URL before it can do anything — and so needs an editor. */
    val needsServer: Boolean,
    /** Whether this kind can serve bit-exact audio when asked. */
    val canServeLossless: Boolean,
    /**
     * Where this kind sits in the walk, low first.
     *
     * Meaningful only while more than one kind exists; kept so the persisted
     * config schema and the registry's sort stay stable if a source is ever
     * added back.
     */
    val rank: Int,
) {
    /**
     * The source the app was built on. It cannot be removed — see
     * [SourceRegistry]. Nothing else in the app can supply a home feed, a
     * radio station or a related-tracks queue.
     */
    YOUTUBE(
        label = "YouTube Music",
        needsServer = false,
        canServeLossless = false,
        rank = 0,
    ),
    ;
}
