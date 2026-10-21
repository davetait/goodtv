package com.keyspaceapps.goodtv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.keyspaceapps.goodtv.R

/** What the Up next screen shows about the next episode. */
@Immutable
data class UpNext(
    /** "S1 E4 · The Long Night". */
    val heading: String,
    val seriesTitle: String,
    /** "52m", when known. */
    val meta: String? = null,
    val summary: String? = null,
    /** The episode's still, else the show's backdrop. */
    val imageUrl: String? = null,
)

/** How long Up next waits (while the credits play) before starting the next episode. */
const val UP_NEXT_COUNTDOWN_MS = 10_000L

/**
 * The geometry of the credits window while Up next shows: top-left, [VIDEO_WIDTH_FRACTION] of the
 * screen, 16:9. [PlayerScreen] shrinks the video into it; this lays out everything around it.
 */
object UpNextLayout {
    const val VIDEO_WIDTH_FRACTION = 0.44f
    val Margin: Dp = 56.dp
    val Gap: Dp = 40.dp
}

/**
 * Up next, laid out around the shrunken credits: the next episode's picture under them, and on
 * the right its title, summary, "Play now" (focused, filling up as [remainingMs] runs down) and
 * "Back to credits".
 */
@Composable
fun UpNextScreen(
    upNext: UpNext,
    remainingMs: Long,
    screenWidth: Dp,
    onPlayNow: () -> Unit,
    onBackToCredits: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val videoWidth = screenWidth * UpNextLayout.VIDEO_WIDTH_FRACTION
    val videoHeight = videoWidth * 9f / 16f
    val playFocus = remember { FocusRequester() }
    var requested by remember { mutableStateOf(false) }
    val seconds = ((remainingMs + 999) / 1000).coerceAtLeast(0)
    val elapsed = (1f - remainingMs.toFloat() / UP_NEXT_COUNTDOWN_MS).coerceIn(0f, 1f)

    Box(modifier.fillMaxSize()) {
        // The next episode's picture, under the credits window.
        upNext.imageUrl?.let { url ->
            val top = UpNextLayout.Margin + videoHeight + 24.dp
            Box(
                Modifier.offset(x = UpNextLayout.Margin, y = top).size(videoWidth * 0.62f, videoWidth * 0.62f * 9f / 16f)
                    .clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f)),
            ) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }

        Column(
            Modifier.fillMaxHeight().padding(start = UpNextLayout.Margin + videoWidth + UpNextLayout.Gap, end = UpNextLayout.Margin, top = UpNextLayout.Margin + 8.dp),
        ) {
            Text(
                stringResource(R.string.player_up_next).uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            Text(upNext.seriesTitle, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                upNext.heading,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            upNext.meta?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.6f))
            }
            upNext.summary?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f), maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CountdownButton(
                    text = stringResource(R.string.player_play_now_in, seconds),
                    elapsed = elapsed,
                    onClick = onPlayNow,
                    modifier = Modifier.focusRequester(playFocus).onPlaced { if (!requested) { requested = true; playFocus.requestFocus() } },
                )
                PillButton(stringResource(R.string.player_back_to_credits), onBackToCredits)
            }
        }
    }
}

/** A white pill whose background fills with the accent as the countdown runs ([elapsed] 0→1). */
@Composable
private fun CountdownButton(text: String, elapsed: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.85f),
            contentColor = Color.Black,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            Box(Modifier.matchParentSize()) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(elapsed).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)))
            }
            Text(
                text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp))
    }
}
