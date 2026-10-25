package com.keyspaceapps.goodtv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.keyspaceapps.goodtv.R
import java.text.NumberFormat

/** What the info box shows; null values haven't been measured (yet) or aren't known. */
@Immutable
data class InfoUi(
    val video: String? = null,
    val bitrateBps: Long? = null,
    val declaredBps: Long? = null,
    val networkBps: Long? = null,
    val bufferAheadMs: Long = 0,
    val droppedFrames: Long = 0,
    val totalFrames: Long = 0,
)

private val Label = Color.White.copy(alpha = 0.62f)
private val Low = Color(0xFFE7B860)
private val Healthy = Color(0xFF7FD69C)

/** The small box Info opens: video, bitrate, network speed, buffer and dropped frames. */
@Composable
fun PlaybackInfoBox(info: InfoUi, modifier: Modifier = Modifier) {
    val measuring = stringResource(R.string.info_measuring)
    val bitrate = when {
        info.bitrateBps != null && info.declaredBps != null ->
            stringResource(R.string.info_stream_says, InfoText.rate(info.bitrateBps), InfoText.rate(info.declaredBps))
        info.bitrateBps != null -> InfoText.rate(info.bitrateBps)
        info.declaredBps != null -> InfoText.rate(info.declaredBps)
        else -> measuring
    }
    val numbers = NumberFormat.getIntegerInstance()
    Column(
        // As wide as the widest row, so labels and values line up without the box spreading across the screen.
        modifier.width(IntrinsicSize.Max).widthIn(min = 260.dp).background(Color(0xFF0A0C10).copy(alpha = 0.8f), RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(stringResource(R.string.info_title), style = MaterialTheme.typography.labelSmall, color = Label)
        InfoRow(stringResource(R.string.info_video), info.video ?: stringResource(R.string.info_unknown))
        InfoRow(stringResource(R.string.info_bitrate), bitrate)
        InfoRow(stringResource(R.string.info_network), info.networkBps?.let(InfoText::rate) ?: measuring)
        InfoRow(
            stringResource(R.string.info_buffer),
            stringResource(R.string.info_buffer_ahead, (info.bufferAheadMs / 1000).toInt()),
            if (InfoText.bufferLow(info.bufferAheadMs)) Low else Healthy,
        )
        InfoRow(stringResource(R.string.info_dropped), stringResource(R.string.info_frames, numbers.format(info.droppedFrames), numbers.format(info.totalFrames)))
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Label, modifier = Modifier.padding(end = 20.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor)
    }
}
