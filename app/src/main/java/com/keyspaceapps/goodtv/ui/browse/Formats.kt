package com.keyspaceapps.goodtv.ui.browse

object Formats {
    /** 5400 → "1h 30m"; 2700 → "45m"; null → null. */
    fun duration(secs: Int?): String? {
        if (secs == null || secs <= 0) return null
        val h = secs / 3600
        val m = (secs % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun year(date: String?): String? = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

    fun rating(r: Double?): String? = r?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }

    fun metaLine(parts: List<String?>): String = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
}
