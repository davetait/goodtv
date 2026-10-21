package com.keyspaceapps.goodtv.data.subtitles

/** Chooses which OpenSubtitles result to offer for a title. */
object SubtitlePicker {
    // "S01E02", "s1.e2", "S01 E02", and the uploader typo "SE02E02" (read as season 2).
    private val EPISODE_CODE = Regex("""[Ss][Ee]?(\d{1,2})[ ._-]?[Ee](\d{1,3})""")

    /**
     * The best English result: no AI or machine translations; for an episode, nothing whose release
     * or file name names a different episode; then not hearing-impaired, trusted uploaders, and the
     * most downloaded first. Null when nothing is usable.
     */
    fun best(results: List<OsSubtitle>, query: SubtitleQuery): OsSubtitle? =
        results.asSequence()
            .filter { !it.aiTranslated && !it.machineTranslated }
            .filter { matchesEpisode(it, query) }
            .sortedWith(compareByDescending<OsSubtitle> { !it.hearingImpaired }.thenByDescending { it.fromTrusted }.thenByDescending { it.downloadCount })
            .firstOrNull()

    /** False only when [query] is an episode and the result's name carries another episode's code. */
    fun matchesEpisode(result: OsSubtitle, query: SubtitleQuery): Boolean {
        if (!query.isEpisode) return true
        val code = EPISODE_CODE.find(result.release) ?: result.fileName?.let { EPISODE_CODE.find(it) } ?: return true
        return code.groupValues[1].toInt() == query.season && code.groupValues[2].toInt() == query.episode
    }
}
