package com.keyspaceapps.goodtv.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Single-line text field for the TV. tv-material has no text field, so this wraps BasicTextField
 * in a focus-highlighted box. Pressing Select/Enter on the focused field opens the on-screen keyboard.
 * The label is shown uppercase above the field and, unless [placeholder] says otherwise, inside it
 * while empty. The keyboard's Next/Done action (or a hardware Enter) moves focus to the next field
 * so the whole form can be filled without leaving the keyboard; D-pad Up/Down leave the field.
 * [fieldModifier] applies to the editable field itself (focus requests, focus order, test tags).
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    last: Boolean = false,
    placeholder: String = label,
    fieldModifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme

    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            interactionSource = interaction,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri,
                imeAction = if (last) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                onDone = { keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down) },
            ),
            modifier = fieldModifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .onPreviewKeyEvent { event ->
                    // Without the keyboard open, Up/Down move between controls rather than the cursor.
                    val direction = when (event.key) {
                        Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionUp -> FocusDirection.Up
                        else -> null
                    }
                    if (direction != null && event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction) else false
                }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                        keyboard?.show(); true
                    } else {
                        false
                    }
                },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                        .border(2.dp, if (focused) colors.primary else colors.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    if (value.isEmpty()) Text(placeholder, color = colors.onSurfaceVariant)
                    inner()
                }
            },
        )
    }
}
