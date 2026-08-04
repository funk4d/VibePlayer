package com.vibeplayer.tv

/**
 * One playable address, with everything the menus need to reason about it.
 *
 * Deliberately free of Android types: every rule about what the viewer may choose lives here
 * and is decided by tests, not by a television.
 */
internal data class Stream(
    val url: String,
    val quality: String,
    val voice: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val watchedPercent: Int = 0,
    val resumePositionMs: Long = 0L,
    val timelineHash: String? = null,
    val resolveUrl: String? = null,
) {
    val isEpisode: Boolean get() = season != null && episode != null
    val height: Int? get() = QualityVariantParser.heightFromLabel(quality)
}

/** Where the viewer currently is. */
internal data class SelectionState(
    val playingUrl: String? = null,
    val quality: String? = null,
    val voice: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /**
     * True once an episode has been chosen inside the player. The quality list the source sent
     * describes the stream it launched, and stops applying the moment the viewer leaves it.
     */
    val leftLaunchedStream: Boolean = false,
)

/**
 * Decides what the viewer may choose, and what choosing it means.
 *
 * The rules are small but they interlock: qualities belong to one episode, voices belong to one
 * episode, and episodes belong to one voice. Getting any of them wrong shows up as another
 * one's menu going empty or leading somewhere else, which is why they are settled together and
 * under test rather than in three places that quietly disagree.
 */
internal class PlaybackSelection(
    private val streams: List<Stream>,
    /** Addresses obtained by asking the source about one episode; they replace the guesswork. */
    private val resolved: List<Stream> = emptyList(),
) {
    private val plain = streams.filter { !it.isEpisode && it.voice == null }

    fun qualities(state: SelectionState): List<Stream> {
        if (resolved.isNotEmpty()) return distinct(resolved)
        if (!state.leftLaunchedStream && plain.isNotEmpty()) return distinct(plain)

        val forEpisode = episodeStreams(state.season, state.episode)
            .filter { sameVoice(it.voice, state.voice) }
        if (forEpisode.size > 1) return distinct(forEpisode)

        // Nothing to choose between: the stream's own variants are the answer, and only the
        // player can see those.
        return emptyList()
    }

    /** Voices of the episode being watched, one entry per voice however it is spelled. */
    fun voices(state: SelectionState): Map<String, List<Stream>> {
        val named = episodeStreams(state.season, state.episode).filter { it.voice != null }
        if (named.isNotEmpty()) return foldAliases(named.groupBy { requireNotNull(it.voice) })

        // Sources without episodes describe each voice as a stream of its own.
        val standalone = streams.filter { !it.isEpisode && it.voice != null }
        return foldAliases(standalone.groupBy { requireNotNull(it.voice) })
    }

    fun seasons(state: SelectionState): List<Int> = streams
        .filter { it.isEpisode && sameVoice(it.voice, state.voice) }
        .mapNotNull { it.season }
        .distinct()
        .sorted()
        .ifEmpty { streams.mapNotNull { it.season }.distinct().sorted() }

    /**
     * Episodes of a season, whatever voice they are in - an episode the current voice lacks is
     * still an episode, and choosing it moves to a voice that has it.
     */
    fun episodes(season: Int?): Map<Int, List<Stream>> = streams
        .filter { it.isEpisode && it.season == season }
        .groupBy { requireNotNull(it.episode) }
        .toSortedMap()

    fun pickEpisode(state: SelectionState, season: Int, episode: Int): Stream? {
        val candidates = episodes(season)[episode].orEmpty()
        if (candidates.isEmpty()) return null
        val inVoice = candidates.filter { sameVoice(it.voice, state.voice) }
        return best(inVoice.ifEmpty { candidates }, state)
    }

    /**
     * Which episode choosing a season means.
     *
     * A season is not something that plays; an episode is. Picking one and having nothing
     * happen is the same to a viewer as the player being broken. The first episode not
     * already finished is where that season is up to.
     */
    fun pickSeason(state: SelectionState, season: Int): Stream? {
        val episodes = episodes(season)
        if (episodes.isEmpty()) return null
        val resumeAt = episodes.entries.firstOrNull { (_, streams) ->
            streams.any { it.watchedPercent < FINISHED_PERCENT }
        } ?: episodes.entries.first()
        return best(
            resumeAt.value.filter { sameVoice(it.voice, state.voice) }.ifEmpty { resumeAt.value },
            state,
        )
    }

    /**
     * The backup addresses that still apply.
     *
     * A source ships them for the stream it was asked about, so they address the launched
     * episode and no other. Falling back to them from a different episode plays the wrong
     * episode - and resumes it at the position the viewer left the first one at, which is how
     * this was found.
     */
    fun reserves(state: SelectionState, launched: List<String>): List<String> =
        if (state.leftLaunchedStream) emptyList() else launched

    fun pickVoice(state: SelectionState, voice: String): Stream? =
        best(voices(state)[voice].orEmpty(), state)

    /** The closest match to what is already being watched, so a switch changes one thing only. */
    fun best(candidates: List<Stream>, state: SelectionState): Stream? {
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.quality.equals(state.quality, ignoreCase = true) }?.let { return it }
        val wantedHeight = state.quality?.let(QualityVariantParser::heightFromLabel)
        if (wantedHeight != null) {
            candidates.minByOrNull { stream ->
                kotlin.math.abs((stream.height ?: FAR_AWAY) - wantedHeight)
            }?.let { return it }
        }
        return candidates.maxByOrNull { it.height ?: 0 }
    }

    private fun episodeStreams(season: Int?, episode: Int?): List<Stream> {
        if (season == null || episode == null) return emptyList()
        return streams.filter { it.season == season && it.episode == episode }
    }

    /**
     * A menu is a set of distinct choices. Sources that name no quality label everything the
     * same, and several such entries differ only in which address they point at.
     */
    private fun distinct(candidates: List<Stream>): List<Stream> {
        val named = candidates.filter { it.height != null }
        return (named.ifEmpty { candidates }).distinctBy { it.quality.lowercase() }
    }

    companion object {
        private const val FAR_AWAY = Int.MAX_VALUE / 2
        private const val FINISHED_PERCENT = 90

        /** "AlexFilm" and "Алексфильм (AlexFilm)" are one voice named twice. */
        fun voiceKey(name: String): String {
            val bracketed = Regex("\\(([^)]{2,})\\)").find(name)?.groupValues?.get(1)
            return (bracketed ?: name).lowercase().filter(Char::isLetterOrDigit).ifEmpty { name.lowercase() }
        }

        fun sameVoice(voice: String?, selected: String?): Boolean {
            if (voice == null || selected == null) return true
            return voiceKey(voice) == voiceKey(selected)
        }

        private fun foldAliases(groups: Map<String, List<Stream>>): Map<String, List<Stream>> {
            val byKey = LinkedHashMap<String, Pair<String, MutableList<Stream>>>()
            groups.forEach { (name, entries) ->
                val key = voiceKey(name)
                val known = byKey[key]
                if (known == null) {
                    byKey[key] = name to entries.toMutableList()
                } else {
                    known.second.addAll(entries)
                    // Keep the shorter spelling: the longer one only repeats it.
                    if (name.length < known.first.length) byKey[key] = name to known.second
                }
            }
            return byKey.values.associate { (name, entries) -> name to entries.toList() }
        }
    }
}
