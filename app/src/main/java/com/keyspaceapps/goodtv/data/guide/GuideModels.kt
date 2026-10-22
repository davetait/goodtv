package com.keyspaceapps.goodtv.data.guide

/** Where a guide came from: the public epgshare01 files, or the provider's own XMLTV. */
enum class GuideOrigin { EPGSHARE, PROVIDER }

data class GuideChannel(val guideId: String, val origin: GuideOrigin, val logoUrl: String?)

data class GuideProgramme(
    val guideId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String?,
    val genres: List<String>,
    val imageUrl: String?,
)

/** A live channel (by its source and provider id) tied to the guide channel that lists its programmes. */
data class ChannelGuideLink(val sourceId: Long, val channelId: String, val guideId: String)
