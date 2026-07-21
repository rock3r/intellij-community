// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.skiko.hostOs

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
         * The stroke represented by a key-down event, or null for modifier-only or unmappable key-downs.
         *
         * Modifier-only key-downs must never participate in stroke matching: the Ctrl arriving ahead of a chord's
         * second stroke would otherwise register as a nonmatching second stroke and cancel the pending chord. An
         * unmappable key-down — macOS `fn`, for instance, arrives as [Key.Unknown] (VK_UNDEFINED) — is likewise
         * dropped: it can never form a real binding, so it must not be recorded as "Unknown keyCode: 0x0" nor match on
         * the live path.
         *
         * ### AltGr aliasing
         *
         * On Windows, AltGr is reported as Ctrl+Alt, and AltGr is a *typing* modifier, not a chord modifier — e.g. on
         * the Italian layout AltGr+E types `€`/`é`. Reporting such a key-down as `Ctrl+Alt+<key>` would let a Ctrl+Alt
         * claim or binding steal the typed character. On the KEY_PRESSED the AltGraph modifier bit is NOT set (it only
         * appears on the following KEY_TYPED); what marks the key-down as AltGr typing is that it carries a printable
         * code point. We therefore drop the Ctrl and Alt flags when a Ctrl+Alt key-down carries a printable character,
         * so it resolves to a bare stroke and never matches a Ctrl+Alt claim/binding. A genuine Ctrl+Alt chord — or an
         * AltGr combination with no printable output, such as AltGr+G — carries no printable code point and is
         * unaffected. (The bridge's [toComposeKeyEvent] applies the same rule at the AWT boundary.)
         */
        public fun fromKeyDownOrNull(event: KeyEvent): JewelKeyStroke? {
            if (event.key in modifierKeys || event.key == Key.Unknown) return null
            val altGrTyping = event.isAltGrTyping()
            return JewelKeyStroke(
                key = event.key,
                ctrl = event.isCtrlPressed && !altGrTyping,
                shift = event.isShiftPressed,
                alt = event.isAltPressed && !altGrTyping,
                meta = event.isMetaPressed,
            )
        }

        /**
         * Whether this Compose key-down is a Windows AltGr *typing* event: Ctrl+Alt held while a printable character is
         * produced (the AltGraph bit is not set on the KEY_PRESSED, so the printable code point is the reliable
         * signal).
         */
        private fun KeyEvent.isAltGrTyping(): Boolean {
            if (!isCtrlPressed || !isAltPressed) return false
            val codePoint = utf16CodePoint
            return codePoint != 0 &&
                codePoint != java.awt.event.KeyEvent.CHAR_UNDEFINED.code &&
                !Character.isISOControl(codePoint)
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
