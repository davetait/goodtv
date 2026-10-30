package com.keyspaceapps.goodtv.ui.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabDefaults
import androidx.tv.material3.TabRow
import androidx.tv.material3.TabRowDefaults
import androidx.tv.material3.Text

/** Near-black for text on the focus pill. */
private val OnPill = Color(0xFF0B0F19)

/**
 * The app's tab rows. Tabs switch on Select, not on focus, so the focused tab and the open one can differ, and
 * each gets its own mark: a bright pill on the focused tab while the row has focus, and a green underline under
 * the open tab, row focused or not. While the row has focus the other tabs dim, so the pill stands out.
 * (tv-material's own pill sits on the open tab and gives the focused one dark text with nothing behind it.)
 */
@Composable
fun MenuTabRow(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var focusedIndex by remember { mutableIntStateOf(selectedIndex) }
    val text = MaterialTheme.colorScheme.onBackground
    val green = MaterialTheme.colorScheme.primary
    TabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier,
        indicator = { positions, rowHasFocus ->
            positions.getOrNull(focusedIndex)?.let {
                TabRowDefaults.PillIndicator(it, rowHasFocus, activeColor = text, inactiveColor = Color.Transparent)
            }
            positions.getOrNull(selectedIndex)?.let {
                TabRowDefaults.UnderlinedIndicator(it, rowHasFocus, activeColor = green, inactiveColor = green)
            }
        },
    ) {
        labels.forEachIndexed { i, label ->
            Tab(
                selected = i == selectedIndex,
                onFocus = { focusedIndex = i },
                onClick = { onSelect(i) },
                colors = TabDefaults.pillIndicatorTabColors(
                    contentColor = text.copy(alpha = 0.6f),
                    inactiveContentColor = text.copy(alpha = 0.7f),
                    selectedContentColor = text,
                    focusedContentColor = OnPill,
                    focusedSelectedContentColor = OnPill,
                ),
            ) {
                Text(
                    text = label,
                    fontWeight = if (i == selectedIndex) FontWeight.SemiBold else null,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}
