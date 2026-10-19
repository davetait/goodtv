package com.keyspaceapps.goodtv.data.xtream

import com.keyspaceapps.goodtv.data.source.SourceConfig
import com.keyspaceapps.goodtv.domain.PlayableRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamUrlsTest {
    private fun cfg(server: String = "http://panel.example:8080", formats: List<String> = listOf("ts", "m3u8")) =
        SourceConfig.Xtream(server = server, username = "u", password = "p", allowedOutputFormats = formats)

    @Test
    fun normalizeAddsSchemeAndStripsSlash() {
        assertEquals("http://my.panel.com:8080", XtreamUrls.normalizeServer("my.panel.com:8080/"))
        assertEquals("http://my.panel.com", XtreamUrls.normalizeServer("  my.panel.com  "))
    }

    @Test
    fun normalizeKeepsHttpsAndPath() {
        assertEquals("https://p.com/base", XtreamUrls.normalizeServer("https://p.com/base/"))
        assertEquals("http://p.com/some/path", XtreamUrls.normalizeServer("p.com/some/path//"))
    }

    @Test
    fun apiUrlHasCredentialsAndAction() {
        val url = XtreamUrls.api(cfg(), "get_live_streams", mapOf("category_id" to "3"))
        assertEquals("/player_api.php", url.encodedPath)
        assertEquals("u", url.queryParameter("username"))
        assertEquals("p", url.queryParameter("password"))
        assertEquals("get_live_streams", url.queryParameter("action"))
        assertEquals("3", url.queryParameter("category_id"))
        assertEquals("http://panel.example:8080/player_api.php?username=u&password=p", XtreamUrls.api(cfg()).toString())
    }

    @Test
    fun liveUsesTsWhenAllowed() {
        assertEquals("http://panel.example:8080/live/u/p/123.ts", XtreamUrls.stream(cfg(), PlayableRef.Live("123")))
    }

    @Test
    fun liveFallsBackToM3u8() {
        assertEquals("http://panel.example:8080/live/u/p/123.m3u8", XtreamUrls.stream(cfg(formats = listOf("m3u8")), PlayableRef.Live("123")))
        assertEquals("http://panel.example:8080/live/u/p/123.m3u8", XtreamUrls.stream(cfg(formats = emptyList()), PlayableRef.Live("123")))
    }

    @Test
    fun movieAndEpisodeUrls() {
        assertEquals("http://panel.example:8080/movie/u/p/456.mkv", XtreamUrls.stream(cfg(), PlayableRef.Movie("456", "mkv")))
        assertEquals("http://panel.example:8080/series/u/p/1001.mp4", XtreamUrls.stream(cfg(), PlayableRef.Episode("1001", "mp4")))
    }

    @Test
    fun credentialsAreUrlEncoded() {
        val c = SourceConfig.Xtream(server = "http://p.com", username = "a b@c", password = "x/y", allowedOutputFormats = listOf("ts"))
        assertEquals("http://p.com/live/a%20b%40c/x%2Fy/1.ts", XtreamUrls.stream(c, PlayableRef.Live("1")))
        val api = XtreamUrls.api(c).toString()
        assertTrue(api, api.contains("username=a%20b%40c") && api.contains("password=x%2Fy"))
    }

    @Test
    fun serverInConfigIsNormalizedWhenBuilding() {
        assertEquals("http://p.com/live/u/p/1.ts", XtreamUrls.stream(cfg(server = "p.com/"), PlayableRef.Live("1")))
    }
}
