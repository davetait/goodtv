package com.keyspaceapps.goodtv.data.debug

import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import com.keyspaceapps.goodtv.data.xtream.XtreamUrls
import com.keyspaceapps.goodtv.domain.SourceRecord

/** Developer-only prefilled source from BuildConfig (populated from local.properties in debug builds). */
object DebugSource {
    fun fromBuildConfig(server: String, username: String, password: String, skip: Boolean): SourceRecord? {
        if (skip || server.isBlank() || username.isBlank() || password.isBlank()) return null
        return SourceRecord(
            id = 0,
            type = SourceType.XTREAM,
            nickname = "Dev",
            config = SourceConfig.Xtream(XtreamUrls.normalizeServer(server), username, password),
            enabled = true,
            lastSyncAt = null,
            lastError = null,
        )
    }
}
