package com.keyspaceapps.goodtv.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onPlaced

/**
 * Which item a screen last had focused. Lives in the screen's nav-scoped ViewModel so it survives
 * pushing the player and coming back, when the composition (and any `remember`) is rebuilt.
 */
class FocusMemory {
    @Volatile var key: String? = null
}

/**
 * Records focus on this item and requests focus once per composition when [isTarget]. A screen
 * computes `isTarget` as "this is the remembered item if it is on screen, else the default one".
 */
@Composable
fun Modifier.rememberedFocus(memory: FocusMemory, key: String, isTarget: Boolean, requested: MutableState<Boolean>): Modifier {
    val requester = remember { FocusRequester() }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) memory.key = key }
        .onPlaced {
            if (isTarget && !requested.value) {
                requested.value = true
                requester.requestFocus()
            }
        }
}

/** The remembered key when it is among [present], else [default]. */
fun FocusMemory.targetIn(present: Collection<String>, default: String?): String? =
    key?.takeIf { it in present } ?: default
