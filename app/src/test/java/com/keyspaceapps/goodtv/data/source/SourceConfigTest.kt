package com.keyspaceapps.goodtv.data.source

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun xtreamRoundTripsThroughJson() {
        val original = SourceConfig.Xtream(
            server = "http://panel.example:8080",
            username = "u",
            password = "p",
            allowedOutputFormats = listOf("ts", "m3u8"),
        )
        val encoded = SourceConfigJson.encode(json, original)
        val decoded = SourceConfigJson.decode(json, encoded)
        assertEquals(original, decoded)
        assertEquals(SourceType.XTREAM, decoded.type)
    }

    @Test
    fun decodeFailsOnUnknownType() {
        assertThrows(SerializationException::class.java) {
            SourceConfigJson.decode(json, """{"type":"bogus"}""")
        }
    }
}
