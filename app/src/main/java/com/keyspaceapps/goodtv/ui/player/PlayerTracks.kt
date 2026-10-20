package com.keyspaceapps.goodtv.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/** What a subtitle or audio track says about itself, enough to name it for a person. */
data class TrackInfo(val language: String?, val label: String?, val channels: Int = 0, val forced: Boolean = false)

/** One choice in the Subtitles or Audio panel. [ref] is null for "Off". */
data class TrackChoice(val label: String, val selected: Boolean, val ref: TrackRef?)

/** Where a track lives in [Tracks], so it can be selected. */
data class TrackRef(val groupIndex: Int, val trackIndex: Int)

object TrackLabels {
    /**
     * "English", "English · SDH", "German · 5.1", "French · Forced". The language comes first, in the
     * viewer's language; the stream's own label is added only when it says something new.
     */
    fun label(info: TrackInfo, fallback: String, display: Locale = Locale.getDefault()): String {
        val language = info.language?.takeUnless { it.isBlank() || it == "und" || it == C.LANGUAGE_UNDETERMINED }
            ?.let { tag ->
                val code = twoLetter(tag)
                Locale.forLanguageTag(code).getDisplayLanguage(display).takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: tag.uppercase()
            }
            ?.replaceFirstChar { it.titlecase(display) }
        val extra = info.label?.trim()?.takeUnless { it.isEmpty() || it.equals(language, ignoreCase = true) }
        val parts = listOfNotNull(
            language ?: extra ?: fallback,
            extra.takeIf { language != null },
            channelLayout(info.channels),
            if (info.forced) "Forced" else null,
        )
        return parts.distinct().joinToString(" · ")
    }

    // ISO 639-2 bibliographic codes (common in MKV) that differ from the terminology ones Java knows.
    private val bibliographic = mapOf(
        "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs", "dut" to "nl", "fre" to "fr",
        "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "per" to "fa",
        "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
    )
    private val threeToTwo: Map<String, String> by lazy {
        Locale.getISOLanguages().mapNotNull { two -> runCatching { Locale.forLanguageTag(two).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { it to two } }.toMap()
    }

    /** "deu", "ger", "de-DE" → "de"; anything unknown is returned as given. */
    fun twoLetter(tag: String): String {
        val primary = tag.substringBefore('-').substringBefore('_').lowercase()
        return if (primary.length == 3) bibliographic[primary] ?: threeToTwo[primary] ?: primary else primary
    }

    fun channelLayout(channels: Int): String? = when (channels) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }

    /** Numbers repeated labels ("English", "English (2)") so every choice is distinguishable. */
    fun disambiguate(labels: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return labels.map { l -> val n = (seen[l] ?: 0) + 1; seen[l] = n; if (n == 1) l else "$l ($n)" }
    }
}

/**
 * The subtitle (with "Off" first) or audio choices for the current [tracks]. Only tracks the
 * device can play are offered.
 */
@OptIn(UnstableApi::class)
fun trackChoices(tracks: Tracks, type: @C.TrackType Int, offLabel: String?, fallback: (Int) -> String): List<TrackChoice> {
    data class Found(val info: TrackInfo, val selected: Boolean, val ref: TrackRef)
    val found = mutableListOf<Found>()
    tracks.groups.forEachIndexed { g, group ->
        if (group.type != type) return@forEachIndexed
        for (t in 0 until group.length) {
            if (!group.isTrackSupported(t)) continue
            val f = group.getTrackFormat(t)
            val info = TrackInfo(f.language, f.label, if (type == C.TRACK_TYPE_AUDIO) f.channelCount else 0, f.selectionFlags and C.SELECTION_FLAG_FORCED != 0)
            found += Found(info, group.isTrackSelected(t), TrackRef(g, t))
        }
    }
    val labels = TrackLabels.disambiguate(found.mapIndexed { i, it -> TrackLabels.label(it.info, fallback(i + 1)) })
    val choices = found.mapIndexed { i, it -> TrackChoice(labels[i], it.selected, it.ref) }
    return if (offLabel == null) choices else listOf(TrackChoice(offLabel, choices.none { it.selected }, null)) + choices
}

/** Selects [ref] for tracks of [type], or turns that type off when [ref] is null. */
@OptIn(UnstableApi::class)
fun Player.selectTrack(type: @C.TrackType Int, ref: TrackRef?) {
    val params = trackSelectionParameters.buildUpon().clearOverridesOfType(type)
    if (ref == null) {
        params.setTrackTypeDisabled(type, true)
    } else {
        val group = currentTracks.groups.getOrNull(ref.groupIndex) ?: return
        params.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, ref.trackIndex))
    }
    trackSelectionParameters = params.build()
}
