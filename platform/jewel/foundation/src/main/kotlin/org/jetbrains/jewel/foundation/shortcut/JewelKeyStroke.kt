// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/**
 * A host-neutral keyboard stroke based on Compose [Key] and modifier flags — deliberately not `javax.swing.KeyStroke`.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class JewelKeyStroke(
    val key: Key,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    public fun matches(event: KeyEvent): Boolean =
        event.key == key &&
            event.isCtrlPressed == ctrl &&
            event.isShiftPressed == shift &&
            event.isAltPressed == alt &&
            event.isMetaPressed == meta

    public fun displayText(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        if (meta) append("Meta+")
        append(key.toString().removePrefix("Key: "))
    }

    public companion object {
        private val modifierKeys =
            setOf(
                Key.CtrlLeft,
                Key.CtrlRight,
                Key.ShiftLeft,
                Key.ShiftRight,
                Key.AltLeft,
                Key.AltRight,
                Key.MetaLeft,
                Key.MetaRight,
            )

        /**
         * The stroke represented by a key-down event, or null for modifier-only key-downs.
         *
         * Modifier-only key-downs must never participate in stroke matching: the Ctrl arriving ahead of a chord's
         * second stroke would otherwise register as a nonmatching second stroke and cancel the pending chord.
         */
        public fun fromKeyDownOrNull(event: KeyEvent): JewelKeyStroke? {
            if (event.key in modifierKeys) return null
            return JewelKeyStroke(
                key = event.key,
                ctrl = event.isCtrlPressed,
                shift = event.isShiftPressed,
                alt = event.isAltPressed,
                meta = event.isMetaPressed,
            )
        }
    }
}

/** A one- or two-stroke shortcut, deliberately matching IJPL `KeyboardShortcut`'s arity. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class JewelKeySequence(val first: JewelKeyStroke, val second: JewelKeyStroke? = null) {
    public fun displayText(): String =
        if (second == null) first.displayText() else "${first.displayText()}, ${second.displayText()}"
}
