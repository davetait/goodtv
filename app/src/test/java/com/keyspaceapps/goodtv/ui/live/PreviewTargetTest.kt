package com.keyspaceapps.goodtv.ui.live

import com.keyspaceapps.goodtv.data.guide.GuideRow
import com.keyspaceapps.goodtv.data.guide.LiveGenre
import com.keyspaceapps.goodtv.data.sports.ChannelCandidate
import com.keyspaceapps.goodtv.domain.LiveChannel
import com.keyspaceapps.goodtv.domain.label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which copy a row's preview plays, and when a focused cell has none. */
class PreviewTargetTest {
    private val now = 1_792_000_800_000L
    private fun feed(id: String, name: String) = ChannelCandidate(LiveChannel(7, id, "c", name, null, null), "US| NEWS")
    private val hd = feed("cn1", "US: CNN HD")
    private val fhd = feed("cn2", "US: CNN FHD")
    private fun row(vararg feeds: ChannelCandidate) = GuideRow("cnn", "CNN", null, LiveGenre.NEWS, feeds.toList(), emptyList(), null, false)
    private fun event(startsMs: Long) = GuideCell("e", CellKind.EVENT, null, now, now + GuideTime.WINDOW_MS, startsMs)

    @Test fun channelCellPlaysThePickersFirstCopy() {
        // The picker lists FHD before HD.
        assertEquals(PreviewTarget("cnn", 7, "cn2", fhd.channel.label), previewTarget(row(hd, fhd), null, now))
    }

    @Test fun anyCellOfALiveRowKeepsTheRowsPreview() {
        val later = GuideCell("p", CellKind.PROGRAMME, null, now + 3_600_000, now + 7_200_000)
        assertEquals("cn2", previewTarget(row(hd, fhd), later, now)?.channelId)
    }

    @Test fun rowWithoutFeedsHasNone() {
        assertNull(previewTarget(row(), null, now))
    }

    @Test fun eventSlotPlaysOnlyWhilePlayable() {
        assertEquals("cn1", previewTarget(row(hd), event(now - 3_600_000), now)?.channelId)
        assertEquals("cn1", previewTarget(row(hd), event(now + 20 * 60_000), now)?.channelId)
        assertNull(previewTarget(row(hd), event(now + 2 * 3_600_000), now))
        assertNull(previewTarget(row(hd), event(now - 5 * 3_600_000), now))
    }
}
