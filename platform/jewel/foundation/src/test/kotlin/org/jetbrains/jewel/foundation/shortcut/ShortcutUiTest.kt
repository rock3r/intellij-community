// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import kotlinx.coroutines.runBlocking
import org.jetbrains.jewel.foundation.JewelFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The UI-test story for shortcuts: a plain Compose UI test can drive dispatch with idiomatic scene key injection
 * ([performKeyInput]) — no window, no AWT hook, no manual host calls — because the resolver root participates in the
 * scene's key-event preview pass. Bindings and claims are also discoverable through semantics ([JewelShortcutActions],
 * [JewelClaimedShortcuts]).
 */
internal class ShortcutUiTest {
    @JvmField @Rule internal val rule: ComposeContentTestRule = createComposeRule()

    private val selectAll = JewelAction(JewelActionId("test.ui.selectAll"), "Select All Rows")
    private val keymap =
        InMemoryJewelKeymap("ui-test").apply {
            bind(selectAll.id, JewelKeySequence(JewelKeyStroke(Key.S, ctrl = true)))
        }
    private val host = JewelShortcutHostState { keymap }

    private var bindingInvocations = 0
    private var claimInvocations = 0
    private var keysSeenByFocusedChild = 0

    private val boundFocus = FocusRequester()
    private val claimerFocus = FocusRequester()

    @Composable
    private fun Content() {
        Box(host.resolverRootModifier) {
            Column {
                Box(
                    Modifier.testTag("bound")
                        .shortcut(selectAll) { bindingInvocations++ }
                        .focusRequester(boundFocus)
                        .focusable()
                )
                Box(
                    Modifier.testTag("claimer")
                        .claimShortcut(JewelKeySequence(JewelKeyStroke(Key.M, ctrl = true))) { claimInvocations++ }
                        .focusRequester(claimerFocus)
                        .focusable()
                        .onKeyEvent {
                            keysSeenByFocusedChild++
                            false
                        }
                )
            }
        }
    }

    private fun focus(requester: FocusRequester) {
        runBlocking {
            rule.runOnIdle { requester.requestFocus() }
            rule.awaitIdle()
        }
    }

    @Test
    fun `keymap command dispatches from injected scene input while its binding is focused`() {
        rule.setContent { Content() }
        focus(boundFocus)

        rule.onNodeWithTag("bound").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
        rule.waitForIdle()
        assertEquals(1, bindingInvocations)

        // Unfocused binding: same injection at the other node must not dispatch.
        focus(claimerFocus)
        rule.onNodeWithTag("claimer").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
        rule.waitForIdle()
        assertEquals(1, bindingInvocations)
    }

    @Test
    fun `focused claim consumes injected input before ordinary key handling`() {
        rule.setContent { Content() }
        focus(claimerFocus)

        rule.onNodeWithTag("claimer").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.M) } }
        rule.waitForIdle()
        assertEquals(1, claimInvocations)
        // Consumed on the preview pass at the resolver root: the focused child's own key handling
        // never saw the claimed stroke (only the Ctrl presses/releases at most).
        val seenAfterClaim = keysSeenByFocusedChild

        // An unbound key falls through to ordinary input and DOES reach the focused child.
        rule.onNodeWithTag("claimer").performKeyInput { pressKey(Key.B) }
        rule.waitForIdle()
        assertEquals(1, claimInvocations)
        assertEquals(seenAfterClaim + 2, keysSeenByFocusedChild) // key-down + key-up of B
    }

    @Test
    fun `bindings and claims are discoverable through semantics`() {
        rule.setContent { Content() }

        rule
            .onNodeWithTag("bound")
            .assert(SemanticsMatcher.expectValue(JewelShortcutActions, listOf(selectAll.id.value)))
        rule.onNodeWithTag("claimer").assert(SemanticsMatcher.expectValue(JewelClaimedShortcuts, listOf("Ctrl+M")))
    }

    @Test
    fun `armed chord resets when focus leaves the resolver root`() {
        val reformat = JewelAction(JewelActionId("test.ui.reformat"), "Reformat")
        val chordKeymap =
            InMemoryJewelKeymap("ui-test-chord").apply {
                bind(
                    reformat.id,
                    JewelKeySequence(JewelKeyStroke(Key.K, ctrl = true), JewelKeyStroke(Key.D, ctrl = true)),
                )
            }
        val chordHost = JewelShortcutHostState { chordKeymap }
        var reformats = 0
        val outsideFocus = FocusRequester()

        rule.setContent {
            Column {
                Box(chordHost.resolverRootModifier) {
                    Box(
                        Modifier.testTag("chordTarget")
                            .shortcut(reformat) { reformats++ }
                            .focusRequester(boundFocus)
                            .focusable()
                    )
                }
                // A focus target OUTSIDE the resolver root: moving here is "focus leaves the surface".
                Box(Modifier.testTag("outside").focusRequester(outsideFocus).focusable())
            }
        }

        focus(boundFocus)
        rule.onNodeWithTag("chordTarget").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
        rule.runOnIdle { assertTrue(chordHost.isAwaitingSecondStroke) }

        // Focus round-trip: the armed chord must not survive it.
        focus(outsideFocus)
        rule.runOnIdle { assertFalse(chordHost.isAwaitingSecondStroke) }
        focus(boundFocus)

        rule.onNodeWithTag("chordTarget").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.D) } }
        rule.waitForIdle()
        assertEquals(0, reformats)

        // And the full chord still works after the reset.
        rule.onNodeWithTag("chordTarget").performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.K) }
            withKeyDown(Key.CtrlLeft) { pressKey(Key.D) }
        }
        rule.waitForIdle()
        assertEquals(1, reformats)
    }

    @Test
    fun `claimShortcut rejects two-stroke sequences`() {
        assertThrows(IllegalArgumentException::class.java) {
            Modifier.claimShortcut(
                JewelKeySequence(JewelKeyStroke(Key.K, ctrl = true), JewelKeyStroke(Key.D, ctrl = true))
            ) {}
        }
    }

    @Test
    fun `dispatch survives a host-state swap without a focus change`() {
        // ShortcutResolverRootElement.update() swaps the state under a stable root node with no focus
        // transitions firing anywhere; registrations live on the root node precisely so they survive this.
        val keymapB =
            InMemoryJewelKeymap("ui-test-b").apply {
                bind(selectAll.id, JewelKeySequence(JewelKeyStroke(Key.S, ctrl = true)))
            }
        val hostB = JewelShortcutHostState { keymapB }
        var currentHost by mutableStateOf(host)

        rule.setContent {
            Box(currentHost.resolverRootModifier) {
                Box(
                    Modifier.testTag("bound")
                        .shortcut(selectAll) { bindingInvocations++ }
                        .focusRequester(boundFocus)
                        .focusable()
                )
            }
        }
        focus(boundFocus)

        rule.onNodeWithTag("bound").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
        rule.waitForIdle()
        assertEquals(1, bindingInvocations)

        rule.runOnIdle { currentHost = hostB }
        rule.waitForIdle()

        rule.onNodeWithTag("bound").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
        rule.waitForIdle()
        assertEquals(2, bindingInvocations)
    }

    @Test
    fun `strict mode makes off-thread dispatch throw`() {
        rule.setContent { Content() }
        rule.waitForIdle()

        val awtEvent =
            java.awt.event.KeyEvent(
                java.awt.Panel(),
                java.awt.event.KeyEvent.KEY_PRESSED,
                0L,
                java.awt.event.KeyEvent.CTRL_DOWN_MASK,
                java.awt.event.KeyEvent.VK_S,
                's',
            )
        val composeEvent = awtEvent.toComposeKeyEvent()

        JewelFlags.strictMode = true
        try {
            var thrown: Throwable? = null
            val thread = Thread { thrown = runCatching { host.onPreviewKeyEvent(composeEvent) }.exceptionOrNull() }
            thread.start()
            thread.join(10_000)

            assertTrue("Expected IllegalStateException, got $thrown", thrown is IllegalStateException)
            assertTrue(thrown!!.message!!.contains("UI thread"))
        } finally {
            JewelFlags.strictMode = false
        }
        assertEquals(0, bindingInvocations)
    }
}
