// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.utf16CodePoint
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs

/**
 * A host-neutral keyboard stroke based on Compose [Key] and modifier flags — deliberately not `javax.swing.KeyStroke`.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class JewelKeyStroke(
    /** Primary Compose key for this stroke. */
    val key: Key,
    /** Whether Control must be held. */
    val ctrl: Boolean = false,
    /** Whether Shift must be held. */
    val shift: Boolean = false,
    /** Whether Alt or Option must be held. */
    val alt: Boolean = false,
    /** Whether Meta or Command must be held. */
    val meta: Boolean = false,
) {
    /** Returns `true` when [event] matches this key and all modifier flags. */
    public fun matches(event: KeyEvent): Boolean =
        event.key == key &&
            event.isCtrlPressed == ctrl &&
            event.isShiftPressed == shift &&
            event.isAltPressed == alt &&
            event.isMetaPressed == meta

    /**
     * A human-readable label for the stroke, rendered the way the host OS does: macOS uses the modifier glyphs `⌃⌥⇧⌘`
     * (in that order, no separators) and key glyphs like `⌫ ⌦ ↩`, every other OS uses `Ctrl+Alt+Shift+Meta+` text. It
     * is display only — never parse it back.
     */
    public fun displayText(): String =
        if (hostOs.isMacOS) {
            buildString {
                if (ctrl) append("⌃") // ⌃
                if (alt) append("⌥") // ⌥
                if (shift) append("⇧") // ⇧
                if (meta) append("⌘") // ⌘
                append(macKeyText())
            }
        } else {
            buildString {
                if (ctrl) append("Ctrl+")
                if (alt) append("Alt+")
                if (shift) append("Shift+")
                if (meta) append("Meta+")
                append(key.toString().removePrefix("Key: "))
            }
        }

    /** macOS shows a handful of keys as glyphs; everything else keeps its plain key label. */
    private fun macKeyText(): String =
        when (key) {
            Key.Backspace -> "⌫" // ⌫
            Key.Delete -> "⌦" // ⌦
            Key.Enter -> "↩" // ↩
            Key.Escape -> "⎋" // ⎋
            Key.Tab -> "⇥" // ⇥
            Key.Spacebar -> "␣" // ␣
            Key.DirectionUp -> "↑" // ↑
            Key.DirectionDown -> "↓" // ↓
            Key.DirectionLeft -> "←" // ←
            Key.DirectionRight -> "→" // →
            else -> key.toString().removePrefix("Key: ")
        }

    /** Factory and event-conversion helpers for [JewelKeyStroke]. */
    public companion object {
        private val modifierKeyCodes =
            setOf(
                java.awt.event.KeyEvent.VK_CONTROL,
                java.awt.event.KeyEvent.VK_SHIFT,
                java.awt.event.KeyEvent.VK_ALT,
                java.awt.event.KeyEvent.VK_ALT_GRAPH,
                java.awt.event.KeyEvent.VK_META,
                java.awt.event.KeyEvent.VK_WINDOWS,
            )

        /**
         * The stroke represented by a key-down event, or `null` when it must not participate in shortcut matching:
         * - Modifier-only key-downs (for example, Ctrl arriving ahead of a chord's second stroke).
         * - Unmappable key-downs (for example, macOS `fn`, which arrives as [Key.Unknown] / `VK_UNDEFINED`).
         * - AltGr / level-three typing key-downs: returning `null` prevents stroke-based claims, open menu scopes, and
         *   keymap bindings from matching either `Ctrl+Alt+<key>` or a bare `<key>` and stealing the typed character.
         *     - On Windows, both an explicit `isAltGraphDown` flag on the underlying AWT event and the `Ctrl+Alt` +
         *       printable-character heuristic (non-zero, non-`CHAR_UNDEFINED`, non-ISO-control) count as AltGr typing,
         *       because Windows JBR reports physical AltGr `KEY_PRESSED` as `Ctrl+Alt` + printable char without setting
         *       the AltGraph bit.
         *     - On Linux, only an explicit `isAltGraphDown` (`ISO_Level3_Shift`) on the underlying AWT event is treated
         *       as AltGr; plain `Ctrl+Alt` is preserved.
         *     - On macOS, Option and `Ctrl+Option` (even with a spurious AltGraph bit) are never treated as AltGr;
         *       original modifiers are preserved.
         */
        public fun fromKeyDownOrNull(event: KeyEvent): JewelKeyStroke? = fromKeyDownOrNull(event, hostOs)

        internal fun fromKeyDownOrNull(event: KeyEvent, os: OS): JewelKeyStroke? {
            if (isModifierOrUnmappable(event) || isAltGrKeyDown(event, os)) {
                return null
            }
            return JewelKeyStroke(
                key = event.key,
                ctrl = event.isCtrlPressed,
                shift = event.isShiftPressed,
                alt = event.isAltPressed,
                meta = event.isMetaPressed,
            )
        }

        internal fun isModifierOrUnmappable(event: KeyEvent): Boolean =
            event.key.nativeKeyCode in modifierKeyCodes || event.key == Key.Unknown

        internal fun isAltGrKeyDown(event: KeyEvent, os: OS = hostOs): Boolean {
            if (isModifierOrUnmappable(event)) return false
            val awtEvent = event.awtEventOrNull
            return when (os) {
                OS.Linux -> awtEvent?.isAltGraphDown == true
                OS.Windows -> isWindowsAltGrKeyDown(event, awtEvent)
                else -> false
            }
        }

        private fun isWindowsAltGrKeyDown(event: KeyEvent, awtEvent: java.awt.event.KeyEvent?): Boolean {
            if (awtEvent?.isAltGraphDown == true) return true
            val ctrl = awtEvent?.isControlDown ?: event.isCtrlPressed
            val alt = awtEvent?.isAltDown ?: event.isAltPressed
            val codePoint = awtEvent?.keyChar?.code ?: event.utf16CodePoint
            return ctrl &&
                alt &&
                codePoint != 0 &&
                codePoint != java.awt.event.KeyEvent.CHAR_UNDEFINED.code &&
                !Character.isISOControl(codePoint)
        }
    }
}

/** A one- or two-stroke shortcut, deliberately matching IJPL `KeyboardShortcut`'s arity. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class JewelKeySequence(
    /** First (or sole) stroke in the sequence. */
    val first: JewelKeyStroke,
    /** Optional second stroke for a chord shortcut. */
    val second: JewelKeyStroke? = null,
) {
    /** Formats this sequence for user display using the host OS conventions. */
    public fun displayText(): String =
        if (second == null) first.displayText() else "${first.displayText()}, ${second.displayText()}"
}
