package com.keyspaceapps.goodtv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

/**
 * 2:3 poster tile with the title (and optional [subtitle]) underneath. Shows [placeholderLabel] (or the
 * first letter) when there is no image. [fitImage] letterboxes instead of cropping, for channel logos.
 * [rating] (0–10) shows as a "★ 7.6" badge in the image's top-left corner; [progress] (0–1) as a bar
 * along the image's bottom edge. [onLongClick] is held Select, or the remote's Menu key. [saved] marks a
 * title on the watch list with a bookmark in the image's top-right corner.
 */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholderLabel: String? = null,
    subtitle: String? = null,
    fitImage: Boolean = false,
    rating: Double? = null,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    saved: Boolean = false,
) {
    val menuKey = if (onLongClick == null) Modifier else Modifier.onKeyEvent { e ->
        if (e.key == Key.Menu && e.type == KeyEventType.KeyUp) { onLongClick(); true } else false
    }
    Card(onClick = onClick, onLongClick = onLongClick, modifier = modifier.then(menuKey).width(140.dp)) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (imageUrl != null) {
                    AsyncImage(model = imageUrl, contentDescription = null, contentScale = if (fitImage) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                } else {
                    Text(placeholderLabel ?: title.take(1).uppercase(), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                progress?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.5f))) {
                        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                    }
                }
                if (saved) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(end = 8.dp).size(width = 18.dp, height = 24.dp)
                            .background(BookmarkGold, BookmarkShape).testTag("watchlist-saved"),
                    )
                }
                Formats.rating(rating)?.let { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = if (subtitle == null) 6.dp else 0.dp),
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                )
            }
        }
    }
}

private val BookmarkGold = Color(0xFFF5C518)

/** A ribbon with a notch cut into its bottom edge. */
private val BookmarkShape = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(size.width / 2f, size.height * 0.75f)
    lineTo(0f, size.height)
    close()
}
