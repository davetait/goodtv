package com.keyspaceapps.goodtv.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * Search field the D-pad can pass through. A text field that takes focus opens the on-screen keyboard
 * and keeps the D-pad, so the field only becomes focusable after Select on the surrounding surface.
 * The keyboard's Search action, or D-pad UP/DOWN, leaves the field; BACK returns focus to the bar.
 */
@Composable
fun HomeSearchBar(query: String, onQueryChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val surface = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)

    // Hand focus back to the surface, then optionally on to the neighbour in [direction].
    fun stopEditing(direction: FocusDirection?) {
        keyboard?.hide()
        surface.requestFocus()
        editing = false
        direction?.let { focusManager.moveFocus(it) }
    }

    LaunchedEffect(editing) {
        if (editing) { field.requestFocus(); keyboard?.show() }
    }

    Surface(
        onClick = { editing = true },
        modifier = modifier.fillMaxWidth().focusRequester(surface),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surfaceVariant,
            focusedContainerColor = colors.surfaceVariant,
            contentColor = colors.onSurface,
            focusedContentColor = colors.onSurface,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, colors.primary), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { stopEditing(FocusDirection.Down) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .focusProperties { canFocus = editing }
                // Once the keyboard is closed the field would otherwise keep the D-pad: UP/DOWN leave it, BACK returns to the bar.
                .onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (event.type == KeyEventType.KeyDown) {
                                stopEditing(if (event.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                            }
                            true
                        }
                        Key.Back -> { if (event.type == KeyEventType.KeyUp) stopEditing(null); true }
                        else -> false
                    }
                }
                .onFocusChanged {
                    if (it.isFocused) hadFocus = true else if (hadFocus) { hadFocus = false; editing = false }
                },
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (query.isEmpty()) Text(hint, color = colors.onSurfaceVariant)
                    inner()
                }
            },
        )
    }
}
