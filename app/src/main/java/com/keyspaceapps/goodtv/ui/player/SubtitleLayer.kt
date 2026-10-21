package com.keyspaceapps.goodtv.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.text.Cue
import androidx.tv.material3.Text

private val CaptionStyle = TextStyle(
    color = Color.White,
    fontSize = 30.sp,
    lineHeight = 40.sp,
    fontWeight = FontWeight.Medium,
    textAlign = TextAlign.Center,
    shadow = Shadow(Color.Black.copy(alpha = 0.9f), blurRadius = 6f),
)

/**
 * Draws the player's current subtitle cues: text cues as centred captions near the bottom, lifted
 * by [bottomInset] (so they clear the controls when those are showing), and bitmap cues (DVB, PGS)
 * where the stream places them.
 */
@Composable
fun SubtitleLayer(cues: List<Cue>, bottomInset: Dp, modifier: Modifier = Modifier) {
    if (cues.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        cues.filter { it.bitmap != null }.forEach { cue -> BitmapCue(cue, width, height) }

        val lines = cues.mapNotNull { it.text?.toString()?.trim()?.takeIf { t -> t.isNotEmpty() } }
        if (lines.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.BottomCenter).padding(start = 120.dp, end = 120.dp, bottom = bottomInset),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                lines.forEach { line ->
                    Text(
                        line,
                        style = CaptionStyle,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BitmapCue(cue: Cue, screenWidth: Dp, screenHeight: Dp) {
    val bitmap = cue.bitmap ?: return
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val w = if (cue.size != Cue.DIMEN_UNSET) screenWidth * cue.size else screenWidth * 0.5f
    val h = if (cue.bitmapHeight != Cue.DIMEN_UNSET) screenHeight * cue.bitmapHeight else w * (bitmap.height.toFloat() / bitmap.width)
    val x = if (cue.position != Cue.DIMEN_UNSET) screenWidth * cue.position - anchorShift(cue.positionAnchor, w) else (screenWidth - w) / 2
    val y = if (cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION) screenHeight * cue.line - anchorShift(cue.lineAnchor, h) else screenHeight - h - 48.dp
    Box(Modifier.offset(x, y).size(w, h)) { Image(image, contentDescription = null, modifier = Modifier.fillMaxSize()) }
}

private fun anchorShift(anchor: Int, extent: Dp): Dp = when (anchor) {
    Cue.ANCHOR_TYPE_MIDDLE -> extent / 2
    Cue.ANCHOR_TYPE_END -> extent
    else -> 0.dp
}
