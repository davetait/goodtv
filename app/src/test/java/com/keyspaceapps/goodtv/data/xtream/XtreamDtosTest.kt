package com.keyspaceapps.goodtv.data.xtream

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamDtosTest {
    private val json = XtreamJson.instance
    private fun fixture(name: String) = javaClass.getResource("/xtream/$name")!!.readText()

    @Test
    fun liveStreamsParseMixedTypes() {
        val list = json.decodeFromString(ListSerializer(XtLiveStream.serializer()), fixture("live_streams_mixed.json"))
        assertEquals(listOf("123", "124"), list.map { it.stream_id })
        assertEquals(listOf("1", "2"), list.map { it.category_id })
        assertEquals(7, list[1].num)
        assertNull(list[1].stream_icon)
    }

    @Test
    fun vodRatingParsesStringOrNumber() {
        val list = json.decodeFromString(ListSerializer(XtVodStream.serializer()), fixture("vod_streams_mixed.json"))
        assertEquals(listOf(8.5, 8.5), list.map { it.rating })
        assertEquals(listOf("456", "457"), list.map { it.stream_id })
        assertEquals(listOf(1609459200L, 1609459201L), list.map { it.added })
    }

    @Test
    fun authBadParses() {
        val auth = json.decodeFromString(XtAuthResponse.serializer(), fixture("auth_bad.json"))
        assertEquals(0, auth.user_info?.auth)
        assertEquals("25461", auth.server_info?.port)
    }

    @Test
    fun authOkAllowedFormats() {
        val auth = json.decodeFromString(XtAuthResponse.serializer(), fixture("auth_ok.json"))
        assertEquals(1, auth.user_info?.auth)
        assertEquals(listOf("ts", "m3u8"), auth.user_info?.allowed_output_formats)
        assertEquals(1735689600L, auth.user_info?.exp_date)
        assertEquals(2, auth.user_info?.max_connections)
    }

    @Test
    fun seriesInfoMapFillsSeasonFromKey() {
        val info = json.decodeFromString(XtSeriesInfoResponse.serializer(), fixture("series_info_map.json"))
        assertEquals(3, info.episodes.size)
        val s2 = info.episodes.single { it.id == "2001" }
        assertEquals(2, s2.season)
        assertEquals("1002", info.episodes[1].id)
        assertEquals(2, info.episodes[1].episode_num)
        assertEquals(2600, info.episodes[1].info?.duration_secs)
        assertEquals(listOf(1, 2), info.seasons.map { it.season_number })
    }

    @Test
    fun seriesInfoArrayParses() {
        val info = json.decodeFromString(XtSeriesInfoResponse.serializer(), fixture("series_info_array.json"))
        assertEquals(3, info.episodes.size)
        assertEquals(listOf(1, 1, 2), info.episodes.map { it.season })
    }

    @Test
    fun vodInfoBackdropSingleString() {
        val info = json.decodeFromString(XtVodInfoResponse.serializer(), fixture("vod_info.json"))
        assertEquals(listOf("http://x/backdrop.jpg"), info.info?.backdrop_path)
        assertEquals(7200, info.info?.duration_secs)
        assertEquals("456", info.movie_data?.stream_id)
    }

    @Test
    fun unknownKeysIgnoredAndCategoryIdsFlex() {
        val cats = json.decodeFromString(ListSerializer(XtCategory.serializer()), fixture("live_categories.json"))
        assertEquals(listOf("1", "2"), cats.map { it.category_id })
        assertEquals(listOf(0, 0), cats.map { it.parent_id })
    }

    @Test
    fun seriesListParsesMixed() {
        val list = json.decodeFromString(ListSerializer(XtSeries.serializer()), fixture("series.json"))
        assertEquals(listOf("789", "790"), list.map { it.series_id })
        assertEquals(7.0, list[1].rating!!, 0.0)
        assertNull(list[1].cover)
    }
}
