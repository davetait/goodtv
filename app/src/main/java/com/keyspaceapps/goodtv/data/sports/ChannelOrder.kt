package com.keyspaceapps.goodtv.data.sports

/**
 * The channel picker's order: North American channels (category "US|" or "CA|") first; within
 * that, English, then French, then other languages; then names saying "LIVE", then the rest, then
 * "Next" (not started yet); then by name.
 */
object ChannelOrder {
    private val FRENCH = listOf("french", "francais")
    private val OTHER = listOf("spanish", "espanol")

    fun sort(list: List<ChannelCandidate>): List<ChannelCandidate> = list.sortedWith(
        compareBy<ChannelCandidate> { if (northAmerican(it)) 0 else 1 }
            .thenBy { language(it) }
            .thenBy { liveness(it) }
            .thenBy { GameMatcher.normalize(it.channel.name) },
    )

    private fun northAmerican(c: ChannelCandidate) = c.categoryName.trimStart().let { it.startsWith("US|") || it.startsWith("CA|") }

    /** 0 English, 1 French, 2 other. */
    private fun language(c: ChannelCandidate): Int {
        val words = " ${GameMatcher.normalize(c.channel.name)} "
        val lang = c.channel.language?.uppercase()
        return when {
            FRENCH.any { " $it " in words } || lang == "FR" -> 1
            OTHER.any { " $it " in words } || " es " in words || (lang != null && lang != "EN" && lang != "US" && lang != "CA") -> 2
            else -> 0
        }
    }

    private fun liveness(c: ChannelCandidate): Int {
        val n = GameMatcher.normalize(c.channel.name)
        return when {
            n.startsWith("live ") -> 0
            n.startsWith("next ") -> 2
            else -> 1
        }
    }
}
