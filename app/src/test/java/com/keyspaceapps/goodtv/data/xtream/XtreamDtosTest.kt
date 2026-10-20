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
        assertEquals(listOf(8.5, 8.5, null, null, null, null), list.map { it.rating })
        assertEquals(listOf("456", "457", "458", "459", "460", "461"), list.map { it.stream_id })
        assertEquals(listOf(1609459200L, 1609459201L, null, null, null, null), list.map { it.added })
        // Real panel values: "603", 0 (unknown), 155, "", "43332-" (stray dash), an IMDb id (not TMDB).
        assertEquals(listOf(603L, null, 155L, null, 43332L, null), XtreamMappers.movies(1, list).map { it.tmdbId })
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
        assertEquals(listOf(1399L, null), XtreamMappers.series(1, list).map { it.tmdbId })
        assertEquals(7.0, list[1].rating!!, 0.0)
        assertNull(list[1].cover)
    }

    @Test
    fun authBooleanParsesAsOneOrZero() {
        val yes = json.decodeFromString(XtAuthResponse.serializer(), """{"user_info":{"auth":true}}""")
        val no = json.decodeFromString(XtAuthResponse.serializer(), """{"user_info":{"auth":false}}""")
        assertEquals(1, yes.user_info?.auth)
        assertEquals(0, no.user_info?.auth)
    }

    @Test
    fun mappersStoreCleanTitlesLanguageAndQuality() {
        val movie = XtreamMappers.movies(1, listOf(XtVodStream(stream_id = "1", name = "4K-AR - The Matrix (1999)"))).single()
        assertEquals("4K-AR - The Matrix (1999)", movie.name) // raw name kept for matching
        assertEquals("The Matrix (1999)", movie.title)
        assertEquals("AR", movie.language)
        assertEquals("4K", movie.quality)
        val series = XtreamMappers.series(1, listOf(XtSeries(series_id = "s", name = "AR-SUBS - Broadchurch (2013) (GB)"))).single()
        assertEquals("Broadchurch (2013)", series.title)
        assertEquals("AR-SUB", series.language)
        val live = XtreamMappers.live(1, listOf(XtLiveStream(stream_id = "l", name = "UK: SKY SPORT NEWS ᴿᴬᵂ"))).single()
        assertEquals("SKY SPORT NEWS", live.title)
        assertEquals("UK", live.language)
    }
}
