package com.keyspaceapps.goodtv.data.guide

import com.keyspaceapps.goodtv.domain.LiveChannel

/**
 * Provider logos that are really placeholders: one image (a category's or an app's badge) used by many
 * channels, or a "generic" image.
 */
object PlaceholderLogos {
    /** Channels sharing one image before it counts as a placeholder. */
    const val MIN_CHANNELS = 20

    fun of(channels: List<LiveChannel>): Set<String> {
        val counts = HashMap<String, Int>()
        for (c in channels) {
            val logo = c.logoUrl?.takeIf { it.isNotBlank() } ?: continue
            counts[logo] = (counts[logo] ?: 0) + 1
        }
        return counts.filter { (logo, n) -> n >= MIN_CHANNELS || logo.contains("generic", ignoreCase = true) }.keys
    }
}
