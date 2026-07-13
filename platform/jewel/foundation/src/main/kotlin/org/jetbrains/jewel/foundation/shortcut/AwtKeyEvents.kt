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
public fun KeyEvent.toComposeKeyEvent(): ComposeKeyEvent =
    ComposeKeyEvent(
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
        isCtrlPressed = isControlDown,
        isMetaPressed = isMetaDown,
        isAltPressed = isAltDown,
        isShiftPressed = isShiftDown,
        nativeEvent = this,
    )
