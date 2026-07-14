// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import java.awt.event.KeyEvent
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.InternalJewelApi

/**
 * The Compose representation of an AWT key event, for host integrations that sit at AWT dispatch stages (the IJPL
 * bridge's claim delivery, tests). Mirrors Compose Desktop's own internal conversion: the `KeyEvent(nativeKeyEvent)`
 * constructor must NOT be called with a raw AWT event — the desktop accessors require the internal representation and
 * throw on anything else.
 */
@ApiStatus.Internal
@InternalJewelApi
@OptIn(InternalComposeUiApi::class)
public fun KeyEvent.toComposeKeyEvent(): ComposeKeyEvent {
    // On Windows, AltGr is reported as Ctrl+Alt: an AltGr key-down has `isControlDown`, `isAltDown`, and
    // `isAltGraphDown` all set. AltGr is a *typing* modifier, not a chord modifier — e.g. German AltGr+Q types `@` —
    // so the aliased Ctrl+Alt must be dropped, or a Ctrl+Alt claim/binding would steal the typed character. We strip
    // it here, at the AWT boundary where `isAltGraphDown` is authoritative, mirroring the platform's
    // `IdeKeyEventDispatcher.removeAltGraph`; the resulting Compose `KeyEvent` reconstructs its native event from these
    // flag arguments, so the AltGraph bit is not otherwise preserved past this point. A genuine Ctrl+Alt chord never
    // sets AltGraph, so it is unaffected. (See also `JewelKeyStroke.fromKeyDownOrNull`, which applies the same rule to
    // Compose-native events that still carry a real AWT `nativeKeyEvent`.)
    val altGraph = isAltGraphDown
    return ComposeKeyEvent(
        key =
            Key(
                nativeKeyCode = keyCode,
                nativeKeyLocation =
                    if (keyLocation == KeyEvent.KEY_LOCATION_UNKNOWN) KeyEvent.KEY_LOCATION_STANDARD else keyLocation,
            ),
        type =
            when (id) {
                KeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
                KeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
                else -> KeyEventType.Unknown
            },
        codePoint = keyChar.code,
        isCtrlPressed = isControlDown && !altGraph,
        isMetaPressed = isMetaDown,
        isAltPressed = isAltDown && !altGraph,
        isShiftPressed = isShiftDown,
        nativeEvent = this,
    )
}
