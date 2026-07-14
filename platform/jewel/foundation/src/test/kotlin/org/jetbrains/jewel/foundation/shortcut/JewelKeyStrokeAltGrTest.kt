// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.ui.input.key.Key
import java.awt.event.KeyEvent as AwtKeyEvent
import javax.swing.JPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Windows AltGr contract for [JewelKeyStroke.fromKeyDownOrNull] (IJPL-212347).
 *
 * On Windows, AltGr is reported as Ctrl+Alt: the AWT key-down carries `isControlDown`, `isAltDown`, and
 * `isAltGraphDown` all set. AltGr is a *typing* modifier — e.g. on the German layout AltGr+Q types `@` — so an AltGr
 * key-down must never resolve to a `Ctrl+Alt` stroke, or a `Ctrl+Alt+Q` claim/binding would steal the typed character.
 * A genuine `Ctrl+Alt` chord never sets AltGraph and must be preserved. This mirrors the platform's
 * `IdeKeyEventDispatcher.removeAltGraph` handling.
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

    @Test
    fun `Windows reports AltGr as Ctrl plus Alt plus AltGraph`() {
        val altGrQ =
            awtKeyDown(
                AwtKeyEvent.VK_Q,
                '@',
                AwtKeyEvent.CTRL_DOWN_MASK or AwtKeyEvent.ALT_DOWN_MASK or AwtKeyEvent.ALT_GRAPH_DOWN_MASK,
            )
        // Oracle for the whole test: the raw event really does alias AltGr as Ctrl+Alt.
        assertTrue(altGrQ.isControlDown)
        assertTrue(altGrQ.isAltDown)
        assertTrue(altGrQ.isAltGraphDown)
    }

    @Test
    fun `AltGr plus Q resolves without Ctrl or Alt so a Ctrl plus Alt plus Q claim cannot steal the typed character`() {
        val altGrQ =
            awtKeyDown(
                AwtKeyEvent.VK_Q,
                '@',
                AwtKeyEvent.CTRL_DOWN_MASK or AwtKeyEvent.ALT_DOWN_MASK or AwtKeyEvent.ALT_GRAPH_DOWN_MASK,
            )

        val stroke = JewelKeyStroke.fromKeyDownOrNull(altGrQ.toComposeKeyEvent())

        assertNotNull(stroke)
        assertEquals(Key.Q, stroke!!.key)
        assertFalse("AltGr-derived Ctrl must be dropped", stroke.ctrl)
        assertFalse("AltGr-derived Alt must be dropped", stroke.alt)
        assertNotEquals(
            "AltGr+Q must not equal (and therefore never match) a Ctrl+Alt+Q claim",
            JewelKeyStroke(Key.Q, ctrl = true, alt = true),
            stroke,
        )
    }

    @Test
    fun `a genuine Ctrl plus Alt plus Q chord is preserved because it never sets AltGraph`() {
        val ctrlAltQ =
            awtKeyDown(
                AwtKeyEvent.VK_Q,
                AwtKeyEvent.CHAR_UNDEFINED,
                AwtKeyEvent.CTRL_DOWN_MASK or AwtKeyEvent.ALT_DOWN_MASK,
            )

        val stroke = JewelKeyStroke.fromKeyDownOrNull(ctrlAltQ.toComposeKeyEvent())

        assertEquals(JewelKeyStroke(Key.Q, ctrl = true, alt = true), stroke)
    }

    @Test
    fun `AltGr plus G with no printable output also drops Ctrl and Alt`() {
        // German AltGr+G produces no character but still aliases as Ctrl+Alt+AltGraph. The engine has no stateful
        // KEY_TYPED wait (unlike the platform dispatcher), so it favors never stealing typed input: AltGr strokes are
        // never treated as Ctrl+Alt chords. Recorded here as the deliberate, tested behavior.
        val altGrG =
            awtKeyDown(
                AwtKeyEvent.VK_G,
                AwtKeyEvent.CHAR_UNDEFINED,
                AwtKeyEvent.CTRL_DOWN_MASK or AwtKeyEvent.ALT_DOWN_MASK or AwtKeyEvent.ALT_GRAPH_DOWN_MASK,
            )

        val stroke = JewelKeyStroke.fromKeyDownOrNull(altGrG.toComposeKeyEvent())

        assertNotNull(stroke)
        assertFalse(stroke!!.ctrl)
        assertFalse(stroke.alt)
    }
}
