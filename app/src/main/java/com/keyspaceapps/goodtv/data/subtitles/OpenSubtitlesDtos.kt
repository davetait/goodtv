@file:Suppress("PropertyName")

package com.keyspaceapps.goodtv.data.subtitles

import kotlinx.serialization.Serializable

@Serializable
internal data class OsSearchPage(val data: List<OsItem> = emptyList())

@Serializable
internal data class OsItem(val attributes: OsAttributes = OsAttributes())

@Serializable
internal data class OsAttributes(
    val language: String? = null,
    val download_count: Int = 0,
    val hearing_impaired: Boolean = false,
    val from_trusted: Boolean? = null,
    val ai_translated: Boolean = false,
    val machine_translated: Boolean = false,
    val release: String? = null,
    val files: List<OsFile> = emptyList(),
)

@Serializable
internal data class OsFile(val file_id: Long = 0, val file_name: String? = null)

@Serializable
internal data class OsDownloadRequest(val file_id: Long, val sub_format: String = "srt")

@Serializable
internal data class OsDownload(val link: String? = null)

@Serializable
internal data class OsLoginRequest(val username: String, val password: String)

@Serializable
internal data class OsLogin(val token: String? = null, val base_url: String? = null)

@Serializable
internal data class OsError(val message: String? = null, val reset_time_utc: String? = null)
