// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.runBlocking
import org.jetbrains.jewel.foundation.actionSystem.provideData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The reactive standalone presentation contract: a control's presentation is a Compose-snapshot-reactive derivation, so
 * it updates with NO manual invalidation when snapshot-backed context data changes, and a hot unrelated datum
 * recomposes only the controls that read it — not every demanded control.
 */
internal class ReactivePresentationTest {
    @JvmField @Rule internal val rule: ComposeContentTestRule = createComposeRule()

    private val delete = JewelAction(JewelActionId("test.reactive.delete"), "Delete")
    private val save = JewelAction(JewelActionId("test.reactive.save"), "Save")
    private val hasSelection = ActionContextKey.create<Boolean>("test.reactive.hasSelection")
    private val hotKey = ActionContextKey.create<Int>("test.reactive.hot")

    private val keymap = InMemoryJewelKeymap("reactive-test")
    private val host = JewelShortcutHostState { keymap }

    private val surfaceFocus = FocusRequester()

    // Per-control recomposition counters and last-observed enablement, recorded in each control's own (parameterless)
    // composable scope so a sibling's recomposition never inflates them — the faithful analogue of separate
    // ActionButton(action) call sites, each of which reads (and therefore subscribes to) its own presentation.
    private var deleteRecompositions = 0
    private var deleteEnabled = false
    private var saveRecompositions = 0
    private var saveEnabled = false

    @Composable
    private fun DeleteControl() {
        val presentation by delete.collectPresentationAsState(host)
        // Consume the value so the control genuinely subscribes, exactly as a real control renders it.
        deleteEnabled = presentation.enabled
        deleteRecompositions++
    }

    @Composable
    private fun SaveControl() {
        val presentation by save.collectPresentationAsState(host)
        saveEnabled = presentation.enabled
        saveRecompositions++
    }

    private fun focusSurface() {
        runBlocking {
            rule.runOnIdle { surfaceFocus.requestFocus() }
            rule.awaitIdle()
        }
    }

    @Test
    fun `presentation updates reactively when a context datum changes, with no manual invalidate`() {
        val selection = mutableStateOf(true)

        rule.setContent {
            Box(host.resolverRootModifier) {
                Box(
                    Modifier.provideData { set(hasSelection.name, selection.value) }
                        .shortcut(delete, update = { enabled = context[hasSelection] == true }) {}
                        .focusRequester(surfaceFocus)
                        .focusable()
                ) {
                    DeleteControl()
                }
            }
        }
        focusSurface()
        rule.runOnIdle { assertTrue("enabled while selection present", deleteEnabled) }

        // Flip the datum. No host.presentations.invalidate() anywhere: the derivation reads the live provider value.
        rule.runOnIdle { selection.value = false }
        rule.waitForIdle()
        rule.runOnIdle { assertFalse("disabled once selection is gone", deleteEnabled) }

        rule.runOnIdle { selection.value = true }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue("re-enabled when selection returns", deleteEnabled) }
    }

    @Test
    fun `the selector overload derives a projection reactively`() {
        val selection = mutableStateOf(true)
        var projected: String? = null

        rule.setContent {
            Box(host.resolverRootModifier) {
                Box(
                    Modifier.provideData { set(hasSelection.name, selection.value) }
                        .shortcut(delete, update = { enabled = context[hasSelection] == true }) {}
                        .focusRequester(surfaceFocus)
                        .focusable()
                ) {
                    val enabledText by delete.collectPresentationAsState(host) { if (it.enabled) "on" else "off" }
                    projected = enabledText
                }
            }
        }
        focusSurface()
        rule.runOnIdle { assertEquals("on", projected) }
        rule.runOnIdle { selection.value = false }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("off", projected) }
    }

    @Test
    fun `a hot unrelated datum recomposes only the control that reads it`() {
        val selection = mutableStateOf(true)
        val hot = mutableIntStateOf(0)

        rule.setContent {
            Box(host.resolverRootModifier) {
                // HOT is the outer provider; the selection provider is inner, so the nearest-first live lookup finds
                // selection and short-circuits before ever reading HOT — Delete never subscribes to the hot datum.
                Box(Modifier.provideData { set(hotKey.name, hot.intValue) }) {
                    Box(
                        Modifier.provideData { set(hasSelection.name, selection.value) }
                            .shortcut(delete, update = { enabled = context[hasSelection] == true }) {}
                            // Save's enablement flips with the hot datum's parity, so its presentation genuinely
                            // changes as the datum churns.
                            .shortcut(save, update = { enabled = (context[hotKey] ?: 0) % 2 == 0 }) {}
                            .focusRequester(surfaceFocus)
                            .focusable()
                    ) {
                        DeleteControl()
                        SaveControl()
                    }
                }
            }
        }
        focusSurface()
        rule.waitForIdle()
        val deleteBaseline = deleteRecompositions
        val saveBaseline = saveRecompositions

        // Churn the hot datum. Delete must not recompose; Save (whose enablement tracks it) must.
        repeat(20) {
            rule.runOnIdle { hot.intValue++ }
            rule.waitForIdle()
        }
        assertEquals("Delete does not recompose on hot-datum churn", deleteBaseline, deleteRecompositions)
        assertTrue("the hot-reading control recomposes with its datum", saveRecompositions > saveBaseline)

        // Flipping selection recomposes Delete only; Save's presentation is unchanged (hot did not move).
        val saveBeforeFlip = saveRecompositions
        rule.runOnIdle { selection.value = false }
        rule.waitForIdle()
        assertTrue("Delete recomposes when its own datum changes", deleteRecompositions > deleteBaseline)
        assertEquals("the hot control is untouched by the selection flip", saveBeforeFlip, saveRecompositions)
    }
}
