package com.keyspaceapps.goodtv.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamMappersTest {
    @Test
    fun liveKeepsEpgChannelId() {
        val live = XtreamMappers.live(
            1,
            listOf(
                XtLiveStream(stream_id = "1", name = "Fox News", epg_channel_id = "FoxNews.us"),
                XtLiveStream(stream_id = "2", name = "No Guide", epg_channel_id = ""),
                XtLiveStream(stream_id = "3", name = "Missing"),
            ),
        )
        assertEquals("FoxNews.us", live[0].epgChannelId)
        assertNull(live[1].epgChannelId)
        assertNull(live[2].epgChannelId)
    }
}
