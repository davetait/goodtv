package com.keyspaceapps.goodtv.ui.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/** How far a row of buttons reaches past its slot: room for a focused tv-material button, which grows by about 10%. */
val RowBleedHorizontal = 16.dp
val RowBleedVertical = 8.dp

/**
 * Lets a scrolling row (which clips at its edges) reach [horizontal] past its slot on the left and right and
 * [vertical] above and below, while taking up only its slot. Give the row the same padding inside
 * ([rowBleedPadding]), so its buttons line up with what's above and a focused one isn't cut off at the edge.
 */
fun Modifier.bleed(horizontal: Dp = RowBleedHorizontal, vertical: Dp = RowBleedVertical): Modifier = layout { measurable, constraints ->
    val h = horizontal.roundToPx()
    val v = vertical.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * h, 2 * v))
    layout(placeable.width - 2 * h, placeable.height - 2 * v) { placeable.place(-h, -v) }
}

/** The padding inside a row with [bleed]'s defaults. */
val rowBleedPadding = PaddingValues(horizontal = RowBleedHorizontal, vertical = RowBleedVertical)
