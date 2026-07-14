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
    // On Windows, AltGr is reported as Ctrl+Alt, and AltGr is a *typing* modifier, not a chord modifier — e.g. on the
    // Italian layout AltGr+E types `€`/`é`. Reporting such a key-down as `Ctrl+Alt+<key>` would let a Ctrl+Alt claim or
    // binding steal the typed character. Crucially, on the KEY_PRESSED the AltGraph bit is NOT set (it only appears on
    // the following KEY_TYPED); what marks the key-down as AltGr typing is that it carries a printable `keyChar`. So we
    // treat a Ctrl+Alt key-down with a printable char as typing and drop the Ctrl/Alt (this also survives the
    // platform's `IdeKeyEventDispatcher.removeAltGraph`, which clears only the modifier bits, not `keyChar`). We still
    // honor `isAltGraphDown` for hosts that do surface it. A genuine Ctrl+Alt chord — or an AltGr combination with no
    // printable output, such as AltGr+G — carries no `keyChar` and is unaffected. (See also
    // `JewelKeyStroke.fromKeyDownOrNull`, which applies the same rule to Compose-native events.)
    val altGraph =
        isAltGraphDown ||
            (isControlDown && isAltDown && keyChar != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(keyChar))
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
