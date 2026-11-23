// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import java.awt.event.KeyEvent as AwtKeyEvent
import javax.swing.JPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the [JewelKeyStroke.fromKeyDownOrNull] contract that a key-down which can never form a binding is dropped rather
 * than recorded. macOS `fn` is the motivating case: it arrives as [Key.Unknown] (VK_UNDEFINED), and a keymap recorder
 * that took it verbatim would bind the garbage stroke "Unknown keyCode: 0x0".
 */
internal class JewelKeyStrokeTest {
    private val source = JPanel()

    private fun awtKeyDown(keyCode: Int, keyChar: Char = AwtKeyEvent.CHAR_UNDEFINED): AwtKeyEvent =
        AwtKeyEvent(source, AwtKeyEvent.KEY_PRESSED, 0L, 0, keyCode, keyChar, AwtKeyEvent.KEY_LOCATION_STANDARD)

    @Test
    fun `an unmappable key-down (VK_UNDEFINED, as macOS fn produces) is dropped`() {
        val event = awtKeyDown(AwtKeyEvent.VK_UNDEFINED).toComposeKeyEvent()
        assertEquals("VK_UNDEFINED should map to Key.Unknown", Key.Unknown, event.key)
        assertNull("An unmappable key-down must not become a stroke", JewelKeyStroke.fromKeyDownOrNull(event))
    }

    @Test
    fun `a mappable key-down still produces its stroke`() {
        val stroke = JewelKeyStroke.fromKeyDownOrNull(awtKeyDown(AwtKeyEvent.VK_A, 'a').toComposeKeyEvent())
        assertEquals(Key.A, stroke?.key)
    }
}
