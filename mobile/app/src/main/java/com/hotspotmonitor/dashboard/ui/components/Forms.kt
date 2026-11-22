package com.hotspotmonitor.dashboard.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay

/**
 * Keeps a focused text field scrolled into view while the space around it changes: the keyboard
 * opening, or a save bar appearing under the list after the first edit. [key] is the field's text,
 * so every edit re-checks after the layout has settled.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.keepInViewWhenFocused(key: Any?): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    // Only the focused field follows the keyboard inset, so the others don't recompose with it.
    val ime = if (focused) WindowInsets.ime.getBottom(LocalDensity.current) else 0
    LaunchedEffect(focused, key, ime) {
        if (focused) {
            delay(120)
            requester.bringIntoView()
        }
    }
    bringIntoViewRequester(requester).onFocusEvent { focused = it.isFocused }
}
