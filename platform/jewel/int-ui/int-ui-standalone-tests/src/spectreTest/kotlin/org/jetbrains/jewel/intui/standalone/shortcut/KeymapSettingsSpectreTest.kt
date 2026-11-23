// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(ExperimentalJewelApi::class)

package org.jetbrains.jewel.intui.standalone.shortcut

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.shortcut.DefaultJewelActionRegistry
import org.jetbrains.jewel.foundation.shortcut.InMemoryJewelKeymap
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionDefinition
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.jetbrains.jewel.foundation.shortcut.JewelKeySequence
import org.jetbrains.jewel.foundation.shortcut.JewelKeyStroke
import org.jetbrains.jewel.intui.standalone.awaitWindow
import org.jetbrains.jewel.intui.standalone.stopApplicationWindow
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.window.Window as JewelWindow
import org.jetbrains.jewel.ui.component.KeymapSettingsPanel
import org.junit.jupiter.api.Test

/**
 * Headful standalone Spectre test for [KeymapSettingsPanel]: interactive key recording, live keymap updates, and
 * conflict detection when two actions share one key sequence.
 */
class KeymapSettingsSpectreTest {
    @Test
    fun `keymap settings panel records shortcuts and surfaces conflicts`(): Unit = runSpectreTest {
        val alpha = JewelAction(JewelActionId("test.keymap.alpha"), "Alpha")
        val beta = JewelAction(JewelActionId("test.keymap.beta"), "Beta")
        val registry =
            DefaultJewelActionRegistry().apply {
                register(JewelActionDefinition(alpha, listOf(JewelKeySequence(JewelKeyStroke(Key.A, ctrl = true)))))
                register(JewelActionDefinition(beta, listOf(JewelKeySequence(JewelKeyStroke(Key.B, ctrl = true)))))
            }
        val keymap = InMemoryJewelKeymap.fromDefaults("spectre-keymap", registry)

        val exitApplication = AtomicReference<(() -> Unit)?>(null)
        val windowRef = AtomicReference<ComposeWindow?>(null)
        val appThread =
            AtomicReference<Thread?>(
                thread(name = "spectre-keymap-window", isDaemon = true) {
                    application(exitProcessOnExit = false) {
                        exitApplication.set(::exitApplication)
                        JewelWindow(onCloseRequest = ::exitApplication, title = "Keymap Settings Spectre") {
                            windowRef.compareAndSet(null, window)
                            IntUiTheme {
                                KeymapSettingsPanel(
                                    keymap = keymap,
                                    registry = registry,
                                    modifier = Modifier.fillMaxSize().padding(16.dp),
                                )
                            }
                        }
                    }
                }
            )

        try {
            val window = windowRef.awaitWindow()
            val automator = ComposeAutomator.inProcess(RobotDriver.synthetic(window))
            automator.waitForNode(text = "Alpha", timeout = 10.seconds)
            automator.waitForIdle()

            // Rebind Alpha (row 0) to Ctrl+R.
            automator.refreshWindows()
            val rebindButtons = automator.findByText("Rebind")
            assertEquals(2, rebindButtons.size)
            automator.click(rebindButtons[0])
            automator.waitForNode(text = "Press a shortcut…", timeout = 5.seconds)
            automator.pressKey(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK)
            automator.waitForIdle()

            val expectedStroke = JewelKeySequence(JewelKeyStroke(Key.R, ctrl = true))
            assertEquals(listOf(expectedStroke), keymap.shortcutsFor(alpha.id))
            automator.refreshWindows()
            assertTrue(automator.allNodes().none { it.text?.startsWith("Conflict on ") == true })

            // Rebind Beta (row 1) to the same Ctrl+R stroke and verify conflict detection renders.
            val remainingRebind = automator.findByText("Rebind")
            assertEquals(2, remainingRebind.size)
            automator.click(remainingRebind[1])
            automator.waitForNode(text = "Press a shortcut…", timeout = 5.seconds)
            automator.pressKey(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK)
            automator.waitForIdle()

            assertEquals(listOf(expectedStroke), keymap.shortcutsFor(beta.id))
            automator.refreshWindows()
            val conflictTexts = automator.allNodes().mapNotNull { it.text }.filter { it.startsWith("Conflict on ") }
            assertEquals(2, conflictTexts.size)
            assertTrue(conflictTexts.any { "test.keymap.beta" in it })
            assertTrue(conflictTexts.any { "test.keymap.alpha" in it })
        } finally {
            stopApplicationWindow(exitApplication, windowRef, appThread)
        }
    }
}
