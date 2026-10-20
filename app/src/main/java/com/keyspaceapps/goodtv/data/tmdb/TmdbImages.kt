package com.keyspaceapps.goodtv.data.tmdb

/**
 * TMDB image URLs are base + size + file path. The base and sizes come from `/configuration`; they
 * have been stable for years, so we use them directly rather than spend a call per launch.
 */
object TmdbImages {
    const val BASE = "https://image.tmdb.org/t/p/"
    const val POSTER = "w500"
    const val BACKDROP = "w1280"

    fun url(path: String?, size: String): String? = path?.takeIf { it.startsWith("/") }?.let { BASE + size + it }
}
