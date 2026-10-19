package com.keyspaceapps.goodtv.data.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Per-source connection settings, persisted as JSON. Add a subclass per [SourceType]. */
@Serializable
sealed class SourceConfig {
    @Serializable
    @SerialName("xtream")
    data class Xtream(
        val server: String,
        val username: String,
        val password: String,
        val allowedOutputFormats: List<String> = emptyList(),
    ) : SourceConfig()

    val type: SourceType
        get() = when (this) {
            is Xtream -> SourceType.XTREAM
        }
}

object SourceConfigJson {
    fun encode(json: Json, config: SourceConfig): String = json.encodeToString(SourceConfig.serializer(), config)
    fun decode(json: Json, text: String): SourceConfig = json.decodeFromString(SourceConfig.serializer(), text)
}
