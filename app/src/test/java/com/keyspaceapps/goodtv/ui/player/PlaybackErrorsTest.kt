package com.keyspaceapps.goodtv.ui.player

import androidx.media3.common.PlaybackException
import com.keyspaceapps.goodtv.R
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackErrorsTest {
    @Test fun failuresGetAPlainReasonByKind() {
        assertEquals(R.string.player_error_network, PlaybackErrors.messageRes(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(R.string.player_error_format, PlaybackErrors.messageRes(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertEquals(R.string.player_error_decoding, PlaybackException.ERROR_CODE_DECODING_FAILED.let(PlaybackErrors::messageRes))
        assertEquals(R.string.player_error_generic, PlaybackErrors.messageRes(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test fun detailIsTheCodeInWords() = assertEquals("decoding failed", PlaybackErrors.detail("ERROR_CODE_DECODING_FAILED"))
}
