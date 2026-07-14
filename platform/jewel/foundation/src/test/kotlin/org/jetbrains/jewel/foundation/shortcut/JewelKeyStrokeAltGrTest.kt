// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.ui.input.key.Key
import java.awt.event.KeyEvent as AwtKeyEvent
import javax.swing.JPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pins the Windows AltGr contract for [JewelKeyStroke.fromKeyDownOrNull] (IJPL-212347).
 *
 * On Windows, AltGr is reported as Ctrl+Alt, and AltGr is a *typing* modifier (e.g. Italian AltGr+E types '€'/'é').
 * Verified against a real physical AltGr key press on Windows/JBR: the KEY_PRESSED reports Ctrl+Alt with the AltGraph
 * bit **not** set and carries the printable char; the AltGraph bit only appears on the following KEY_TYPED. So the
 * reliable key-down signal is "Ctrl+Alt held while a printable character is produced" — which must resolve to a bare
 * stroke, or a Ctrl+Alt claim/binding would steal the typed character. A genuine Ctrl+Alt chord, or an AltGr
 * combination with no printable output (AltGr+G), carries no printable char and stays a chord.
 */
internal class JewelKeyStrokeAltGrTest {
    private val source = JPanel()

    private fun awtKeyDown(keyCode: Int, keyChar: Char, modifiersEx: Int): AwtKeyEvent =
        AwtKeyEvent(
            source,
            AwtKeyEvent.KEY_PRESSED,
            0L,
            modifiersEx,
            keyCode,
            keyChar,
            AwtKeyEvent.KEY_LOCATION_STANDARD,
        )

    private val ctrlAlt = AwtKeyEvent.CTRL_DOWN_MASK or AwtKeyEvent.ALT_DOWN_MASK

    @Test
    fun `a real Windows AltGr key-down is Ctrl plus Alt with a printable char and no AltGraph bit`() {
        // Oracle for the whole test, matching the observed physical-press behavior on Windows/JBR.
        val altGrE = awtKeyDown(AwtKeyEvent.VK_E, 'é', ctrlAlt)
        assertEquals(true, altGrE.isControlDown)
        assertEquals(true, altGrE.isAltDown)
        assertEquals(false, altGrE.isAltGraphDown) // NOT set on the KEY_PRESSED
    }

    @Test
    fun `AltGr plus E resolves without Ctrl or Alt so a Ctrl plus Alt plus E claim cannot steal the typed character`() {
        val altGrE = awtKeyDown(AwtKeyEvent.VK_E, 'é', ctrlAlt)

        val stroke = JewelKeyStroke.fromKeyDownOrNull(altGrE.toComposeKeyEvent())

        assertNotNull(stroke)
        assertEquals(Key.E, stroke!!.key)
        assertFalse("AltGr-typing Ctrl must be dropped", stroke.ctrl)
        assertFalse("AltGr-typing Alt must be dropped", stroke.alt)
        assertNotEquals(
            "AltGr+E must not equal (and therefore never match) a Ctrl+Alt+E claim",
            JewelKeyStroke(Key.E, ctrl = true, alt = true),
            stroke,
        )
    }

    @Test
    fun `a genuine Ctrl plus Alt plus Q chord with no printable output is preserved`() {
        val ctrlAltQ = awtKeyDown(AwtKeyEvent.VK_Q, AwtKeyEvent.CHAR_UNDEFINED, ctrlAlt)

        val stroke = JewelKeyStroke.fromKeyDownOrNull(ctrlAltQ.toComposeKeyEvent())

        assertEquals(JewelKeyStroke(Key.Q, ctrl = true, alt = true), stroke)
    }

    @Test
    fun `AltGr plus G with no printable output stays a Ctrl plus Alt chord`() {
        // German/Italian AltGr+G produces no character; with no printable code point it is indistinguishable from a
        // real Ctrl+Alt+G chord and is left as one, matching the platform's behavior for a non-typing AltGr combo.
        val altGrG = awtKeyDown(AwtKeyEvent.VK_G, AwtKeyEvent.CHAR_UNDEFINED, ctrlAlt)

        val stroke = JewelKeyStroke.fromKeyDownOrNull(altGrG.toComposeKeyEvent())

        assertEquals(JewelKeyStroke(Key.G, ctrl = true, alt = true), stroke)
    }

    @Test
    fun `an AltGraph-flagged Ctrl plus Alt key-down is also treated as typing`() {
        // Some hosts/layouts do surface isAltGraphDown on the key-down; honor it as well.
        val altGraphQ = awtKeyDown(AwtKeyEvent.VK_Q, '@', ctrlAlt or AwtKeyEvent.ALT_GRAPH_DOWN_MASK)

        val stroke = JewelKeyStroke.fromKeyDownOrNull(altGraphQ.toComposeKeyEvent())

        assertNotNull(stroke)
        assertFalse(stroke!!.ctrl)
        assertFalse(stroke.alt)
    }
}
