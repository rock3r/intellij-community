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
         *
         * ### AltGr aliasing
         *
         * On Windows, AltGr is reported as Ctrl+Alt: an AltGr key-down carries both `isCtrlPressed` and `isAltPressed`
         * (the underlying AWT event has `isControlDown`, `isAltDown`, and `isAltGraphDown` all set). AltGr is a
         * *typing* modifier, not a chord modifier — e.g. on the German layout AltGr+Q types `@`. Reporting such a
         * stroke as `Ctrl+Alt+Q` would let a Ctrl+Alt+Q claim or binding steal the typed character. We therefore drop
         * the Ctrl and Alt flags when the originating event is AltGr-derived, mirroring the platform's
         * `IdeKeyEventDispatcher.removeAltGraph` semantics. A genuine Ctrl+Alt chord never sets AltGraph, so it is
         * unaffected. Compose exposes no `isAltGraphPressed`, and its own AWT→Compose conversion folds AltGraph *into*
         * `isAltPressed` rather than out of it, so we read the flag off the preserved native AWT event (the bridge's
         * [toComposeKeyEvent] and Compose Desktop both keep it as `nativeKeyEvent`).
         */
        public fun fromKeyDownOrNull(event: KeyEvent): JewelKeyStroke? {
            if (event.key in modifierKeys) return null
            val altGraph = event.isAltGraphDerived()
            return JewelKeyStroke(
                key = event.key,
                ctrl = event.isCtrlPressed && !altGraph,
                shift = event.isShiftPressed,
                alt = event.isAltPressed && !altGraph,
                meta = event.isMetaPressed,
            )
        }

        /**
         * Whether this Compose key event originates from an AltGr press, read from the preserved native AWT event.
         * Returns `false` for events without an AWT native event (e.g. synthetic events in non-desktop hosts).
         */
        private fun KeyEvent.isAltGraphDerived(): Boolean =
            try {
                (nativeKeyEvent as? java.awt.event.KeyEvent)?.isAltGraphDown == true
            } catch (_: Throwable) {
                false
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
