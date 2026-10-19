package com.keyspaceapps.goodtv.data.xtream

import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.domain.PlayableRef
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

object XtreamUrls {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /** Trims, adds `http://` when no scheme was typed, and strips trailing slashes. Keeps port and path. */
    fun normalizeServer(input: String): String {
        var s = input.trim()
        if (!schemeRegex.containsMatchIn(s)) s = "http://$s"
        return s.trimEnd('/')
    }

    fun api(cfg: SourceConfig.Xtream, action: String? = null, params: Map<String, String> = emptyMap()): HttpUrl {
        val builder = normalizeServer(cfg.server).toHttpUrl().newBuilder()
            .addPathSegment("player_api.php")
            .addEncodedQueryParameter("username", encode(cfg.username))
            .addEncodedQueryParameter("password", encode(cfg.password))
        if (action != null) builder.addEncodedQueryParameter("action", encode(action))
        params.forEach { (k, v) -> builder.addEncodedQueryParameter(encode(k), encode(v)) }
        return builder.build()
    }

    /** Live prefers raw `.ts` (most reliable on real panels); falls back to `.m3u8`. */
    fun stream(cfg: SourceConfig.Xtream, ref: PlayableRef): String {
        val base = "${normalizeServer(cfg.server)}"
        val creds = "${encode(cfg.username)}/${encode(cfg.password)}"
        return when (ref) {
            is PlayableRef.Live -> {
                val ext = if ("ts" in cfg.allowedOutputFormats) "ts" else "m3u8"
                "$base/live/$creds/${encode(ref.id)}.$ext"
            }
            is PlayableRef.Movie -> "$base/movie/$creds/${encode(ref.id)}.${encode(ref.ext)}"
            is PlayableRef.Episode -> "$base/series/$creds/${encode(ref.id)}.${encode(ref.ext)}"
        }
    }

    /** RFC 3986 percent-encoding keeping only unreserved characters. Same output for path and query. */
    fun encode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            val unreserved = byte >= 0 && (c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '.' || c == '_' || c == '~')
            if (unreserved) append(c) else append('%').append("%02X".format(byte.toInt() and 0xff))
        }
    }
}
