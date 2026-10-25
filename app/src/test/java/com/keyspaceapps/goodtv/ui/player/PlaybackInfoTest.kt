package com.keyspaceapps.goodtv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackInfoTest {
    private var now = 0L
    private fun meter() = TrafficMeter(clock = { now })

    @Test fun networkSpeedIsBytesOverTimeSpentDownloading() {
        val m = meter()
        m.started(); m.received(1_250_000); now = 1_000; m.ended() // 10 Mbit in a second
        assertEquals(10_000_000L, m.networkBps())
        // A full buffer: nothing downloads for a minute, and the speed stays what the downloads showed.
        now = 61_000
        assertEquals(10_000_000L, m.networkBps())
        // A burst at 40 Mbit/s: the average over the 2 s spent downloading is 25 Mbit/s.
        m.started(); m.received(5_000_000); now = 62_000; m.ended()
        assertEquals(25_000_000L, m.networkBps())
    }

    @Test fun aDownloadStillRunningCountsItsTimeSoFar() {
        val m = meter()
        m.started(); m.received(250_000); now = 500 // a live stream's endless download
        assertEquals(4_000_000L, m.networkBps())
    }

    @Test fun overlappingDownloadsCountTheirTimeOnce() {
        val m = meter()
        m.started(); m.started() // video and audio pieces at once
        m.received(500_000); m.received(500_000)
        now = 1_000; m.ended(); m.ended()
        assertEquals(8_000_000L, m.networkBps())
    }

    @Test fun noDownloadsYetHaveNoSpeed() {
        assertNull(meter().networkBps())
    }

    @Test fun bitrateIsBytesReceivedPerSecondOfVideoReceived() {
        val rates = BitrateMeter()
        rates.reset(bytes = 0, fromMs = 0)
        assertNull("under 2 s of video is too little to say", rates.bitrateBps(bytes = 500_000, bufferedMs = 1_000))
        assertEquals(7_718_814L, rates.bitrateBps(bytes = 14_472_778, bufferedMs = 15_000)) // the channel that comes in 15 s pieces
    }

    @Test fun aSeekStartsTheMeasurementAgain() {
        val rates = BitrateMeter()
        rates.reset(bytes = 0, fromMs = 0)
        rates.reset(bytes = 10_000_000, fromMs = 600_000) // seeked to 10:00
        assertNull(rates.bitrateBps(bytes = 10_000_000, bufferedMs = 600_500))
        assertEquals(2_000_000L, rates.bitrateBps(bytes = 10_750_000, bufferedMs = 603_000))
    }

    @Test fun nothingDownloadedMeansNoMeasuredBitrate() {
        val rates = BitrateMeter()
        rates.reset(bytes = 0, fromMs = 0)
        assertNull("a local file: video arrives, no network bytes", rates.bitrateBps(bytes = 0, bufferedMs = 6_000))
    }

    @Test fun ratesReadInMbpsOrKbps() {
        assertEquals("5.8 Mbps", InfoText.rate(5_800_000))
        assertEquals("42 Mbps", InfoText.rate(42_100_000))
        assertEquals("850 kbps", InfoText.rate(850_000))
    }

    @Test fun videoLineNamesSizeFrameRateAndCodec() {
        assertEquals("1920×1080 · 23.976 fps · H.264", InfoText.video(1920, 1080, 23.976f, "video/avc"))
        assertEquals("3840×2160 · 50 fps · H.265", InfoText.video(3840, 2160, 50f, "video/hevc"))
        assertEquals("1280×720 · AV1", InfoText.video(1280, 720, -1f, "video/av01"))
        assertEquals("720×576 · MPEG-2", InfoText.video(720, 576, 0f, "video/mpeg2"))
        assertEquals("640×360 · video/x-unknown", InfoText.video(640, 360, -1f, "video/x-unknown"))
        assertNull(InfoText.video(0, 0, -1f, null))
    }

    @Test fun bufferUnderFiveSecondsIsLow() {
        assertEquals(true, InfoText.bufferLow(4_999))
        assertEquals(false, InfoText.bufferLow(5_000))
    }
}
