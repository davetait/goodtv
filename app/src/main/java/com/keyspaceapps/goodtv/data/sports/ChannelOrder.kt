package com.keyspaceapps.goodtv.data.sports

/**
 * The channel picker's order: North American channels (category "US|" or "CA|") first; within
 * that, English, then French, then other languages; then names saying "LIVE", then the rest, then
 * "Next" (not started yet); then by name.
 */
object ChannelOrder {
    private val FRENCH = listOf("french", "francais")
    private val OTHER = listOf("spanish", "espanol")

    /** A channel with its sort key, worked out once rather than on both sides of every comparison. */
    private class Keyed(val c: ChannelCandidate, val region: Int, val language: Int, val liveness: Int, val name: String)

    private val ORDER = compareBy<Keyed>({ it.region }, { it.language }, { it.liveness }, { it.name })

    fun sort(list: List<ChannelCandidate>): List<ChannelCandidate> = list.map { c ->
        val name = GameMatcher.normalize(c.channel.name)
        Keyed(c, if (northAmerican(c)) 0 else 1, language(c, name), liveness(name), name)
    }.sortedWith(ORDER).map { it.c }

    private fun northAmerican(c: ChannelCandidate) = c.categoryName.trimStart().let { it.startsWith("US|") || it.startsWith("CA|") }

    /** 0 English, 1 French, 2 other. */
    private fun language(c: ChannelCandidate, name: String): Int {
        val words = " $name "
        val lang = c.channel.language?.uppercase()
        return when {
            FRENCH.any { " $it " in words } || lang == "FR" -> 1
            OTHER.any { " $it " in words } || " es " in words || (lang != null && lang != "EN" && lang != "US" && lang != "CA") -> 2
            else -> 0
        }
    }

    private fun liveness(n: String): Int {
        return when {
            n.startsWith("live ") -> 0
            n.startsWith("next ") -> 2
            else -> 1
        }
    }
}
