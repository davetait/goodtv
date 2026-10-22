package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.domain.LiveChannel

/**
 * Provider logos that are really placeholders: one image (a category's or an app's badge) shared by many
 * different channels. Copies of one channel sharing its own logo don't count, since they're one guide row.
 */
object PlaceholderLogos {
    /** Different guide rows sharing one image before it counts as a placeholder. */
    const val MIN_ROWS = 6

    fun of(channels: List<LiveChannel>): Set<String> {
        val rows = HashMap<String, MutableSet<String>>()
        for (c in channels) {
            val logo = c.logoUrl?.takeIf { it.isNotBlank() } ?: continue
            val keys = rows.getOrPut(logo) { HashSet() }
            if (keys.size < MIN_ROWS) keys += ChannelIdentity.of(c.name, "").rowKey
        }
        return rows.filterValues { it.size >= MIN_ROWS }.keys
    }
}
