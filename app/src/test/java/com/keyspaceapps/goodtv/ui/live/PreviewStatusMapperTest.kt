package com.keyspaceapps.goodtv.ui.live

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/** How the app's preview player's events become a [PreviewStatus]. */
class PreviewStatusMapperTest {
    private val mapper = PreviewStatusMapper()

    @Test fun startsLoadingThenPlays() {
        assertEquals(PreviewStatus.LOADING, mapper.status.value)
        mapper.onPlaybackState(Player.STATE_BUFFERING)
        assertEquals(PreviewStatus.LOADING, mapper.status.value)
        mapper.onIsPlaying(true)
        assertEquals(PreviewStatus.PLAYING, mapper.status.value)
    }

    @Test fun rebufferingKeepsThePicture() {
        mapper.onIsPlaying(true)
        mapper.onIsPlaying(false)
        mapper.onPlaybackState(Player.STATE_BUFFERING)
        assertEquals(PreviewStatus.PLAYING, mapper.status.value)
    }

    @Test fun anErrorFails() {
        mapper.onIsPlaying(true)
        mapper.onError()
        assertEquals(PreviewStatus.FAILED, mapper.status.value)
    }

    @Test fun aLiveStreamThatEndsFails() {
        mapper.onIsPlaying(true)
        mapper.onPlaybackState(Player.STATE_ENDED)
        assertEquals(PreviewStatus.FAILED, mapper.status.value)
    }

    @Test fun aRetryAfterAFailureLoadsThenPlaysAgain() {
        mapper.onIsPlaying(true)
        mapper.onError()
        // PlayerScreen's retry: prepare() then play() on the same player.
        mapper.onPlaybackState(Player.STATE_BUFFERING)
        assertEquals(PreviewStatus.LOADING, mapper.status.value)
        mapper.onPlaybackState(Player.STATE_READY)
        mapper.onIsPlaying(true)
        assertEquals(PreviewStatus.PLAYING, mapper.status.value)
    }

    @Test fun playingAfterAFailureRecoversEvenWithoutBuffering() {
        mapper.onError()
        mapper.onIsPlaying(true)
        assertEquals(PreviewStatus.PLAYING, mapper.status.value)
    }
}
