package com.keyspaceapps.goodtv.data.debug

import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.data.source.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugSourceTest {
    @Test
    fun returnsNullWhenAnyBlank() {
        assertNull(DebugSource.fromBuildConfig("", "demo", "demo", skip = false))
        assertNull(DebugSource.fromBuildConfig("http://10.0.2.2:8089", "", "demo", skip = false))
        assertNull(DebugSource.fromBuildConfig("http://10.0.2.2:8089", "demo", " ", skip = false))
    }

    @Test
    fun returnsNullWhenSkipped() {
        assertNull(DebugSource.fromBuildConfig("http://10.0.2.2:8089", "demo", "demo", skip = true))
    }

    @Test
    fun buildsXtreamRecord() {
        val r = DebugSource.fromBuildConfig("10.0.2.2:8089/", "demo", "demo", skip = false)!!
        assertEquals("Dev", r.nickname)
        assertEquals(0L, r.id)
        assertEquals(SourceType.XTREAM, r.type)
        assertTrue(r.enabled)
        assertEquals(SourceConfig.Xtream("http://10.0.2.2:8089", "demo", "demo"), r.config)
    }
}
