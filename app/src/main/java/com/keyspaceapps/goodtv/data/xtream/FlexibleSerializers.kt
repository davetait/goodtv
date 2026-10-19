package com.keyspaceapps.goodtv.data.xtream

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Xtream panels are inconsistent about JSON types: the same field arrives as "12", 12, 12.0,
 * "" or null depending on the panel. These serializers accept any primitive and coerce it.
 */

private fun Decoder.primitiveContent(): String? {
    val element = (this as JsonDecoder).decodeJsonElement()
    return when (element) {
        is JsonNull -> null
        is JsonPrimitive -> element.content.takeUnless { it.isBlank() || it == "null" }
        else -> null
    }
}

object FlexStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexString", PrimitiveKind.STRING).nullable
    override fun deserialize(decoder: Decoder): String? = decoder.primitiveContent()
    override fun serialize(encoder: Encoder, value: String?) =
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
}

object FlexIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexInt", PrimitiveKind.INT).nullable
    override fun deserialize(decoder: Decoder): Int? = decoder.primitiveContent()?.toDoubleOrNull()?.toInt()
    override fun serialize(encoder: Encoder, value: Int?) =
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
}

object FlexLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexLong", PrimitiveKind.LONG).nullable
    override fun deserialize(decoder: Decoder): Long? = decoder.primitiveContent()?.toDoubleOrNull()?.toLong()
    override fun serialize(encoder: Encoder, value: Long?) =
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
}

object FlexDoubleSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexDouble", PrimitiveKind.DOUBLE).nullable
    override fun deserialize(decoder: Decoder): Double? = decoder.primitiveContent()?.toDoubleOrNull()
    override fun serialize(encoder: Encoder, value: Double?) =
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
}

/** Accepts a JSON array of strings, a single string, or null/absent → list. */
object FlexStringListSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): List<String> {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
            is JsonPrimitive -> if (element is JsonNull || element.content.isBlank()) emptyList() else listOf(element.content)
            else -> emptyList()
        }
    }
    override fun serialize(encoder: Encoder, value: List<String>) = delegate.serialize(encoder, value)
}

/**
 * `get_series_info.episodes` is a map keyed by season number ("1": [...]) on most panels and an
 * array of per-season arrays on some. Flattens either to one list, filling `season` from the key
 * or array index when the episode lacks it.
 */
object EpisodesSerializer : KSerializer<List<XtEpisode>> {
    private val delegate = ListSerializer(XtEpisode.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<XtEpisode> {
        val jsonDecoder = decoder as JsonDecoder
        val json = jsonDecoder.json
        fun decodeOne(el: JsonElement, season: Int?): XtEpisode? =
            (el as? JsonObject)?.let { obj ->
                val ep = json.decodeFromJsonElement(XtEpisode.serializer(), obj)
                if (ep.season == null) ep.copy(season = season) else ep
            }
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonObject -> element.entries.flatMap { (key, value) ->
                val season = key.toIntOrNull()
                (value as? JsonArray)?.mapNotNull { decodeOne(it, season) } ?: emptyList()
            }
            is JsonArray -> element.flatMapIndexed { index, inner ->
                when (inner) {
                    is JsonArray -> inner.mapNotNull { decodeOne(it, index + 1) }
                    is JsonObject -> listOfNotNull(decodeOne(inner, null))
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }

    override fun serialize(encoder: Encoder, value: List<XtEpisode>) = delegate.serialize(encoder, value)
}
