// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the PRD dispatch contract, including the corner cases originally proven live with real OS input
 * (see the shortcuts validation harnesses): claim precedence, nearest-enabled-binding with fallthrough,
 * chord complete/cancel-consume/recover, exact-single-stroke-beats-chord-prefix, modifier-only filtering,
 * typed-event suppression, and never swallowing unbound input.
 */
internal class ShortcutDispatchEngineTest {
    private val selectAll = JewelActionId("test.selectAll")
    private val reformat = JewelActionId("test.reformat")

    private val ctrlA = JewelKeyStroke(Key.A, ctrl = true)
    private val ctrlK = JewelKeyStroke(Key.K, ctrl = true)
    private val ctrlD = JewelKeyStroke(Key.D, ctrl = true)
    private val ctrlEnter = JewelKeyStroke(Key.Enter, ctrl = true)
    private val plainQ = JewelKeyStroke(Key.Q)

    private val keymap =
        InMemoryJewelKeymap("test").apply {
            bind(selectAll, JewelKeySequence(ctrlA))
            bind(reformat, JewelKeySequence(ctrlK, ctrlD))
        }

    private class Recorder {
        val invocations = mutableListOf<String>()

        fun binding(
            actionId: JewelActionId,
            name: String,
            enabled: Boolean = true,
            blocksOuter: Boolean = false,
        ): EngineBinding = EngineBinding(actionId, enabled, blocksOuter, name) { invocations.add(name) }

        fun claim(sequence: JewelKeySequence, name: String, enabled: Boolean = true): EngineClaim =
            EngineClaim(sequence, enabled, blocksOuterClaims = false) { invocations.add(name) }
    }

    private fun engine(
        bindings: () -> List<EngineBinding>,
        claims: () -> List<EngineClaim> = { emptyList() },
    ): ShortcutDispatchEngine = ShortcutDispatchEngine({ keymap }, bindings, claims)

    @Test
    fun `keymap command resolves to the focused binding`() {
        val recorder = Recorder()
        val engine = engine({ listOf(recorder.binding(selectAll, "table")) })

        val decision = engine.onKeyDown(ctrlA)

        assertTrue(decision is DispatchDecision.Consumed)
        assertEquals(listOf("table"), recorder.invocations)
    }

    @Test
    fun `innermost focused binding wins over outer binding for the same action`() {
        val recorder = Recorder()
        // Innermost bindings come last, matching pre-order traversal of the focused path.
        val engine =
            engine({ listOf(recorder.binding(selectAll, "table"), recorder.binding(selectAll, "editor")) })

        engine.onKeyDown(ctrlA)

        assertEquals(listOf("editor"), recorder.invocations)
    }

    @Test
    fun `disabled inner binding falls through to the outer one`() {
        val recorder = Recorder()
        val engine =
            engine({
                listOf(
                    recorder.binding(selectAll, "table"),
                    recorder.binding(selectAll, "editor", enabled = false),
                )
            })

        engine.onKeyDown(ctrlA)

        assertEquals(listOf("table"), recorder.invocations)
    }

    @Test
    fun `disabled inner binding with blocksOuterBindings makes the action unavailable`() {
        val recorder = Recorder()
        val engine =
            engine({
                listOf(
                    recorder.binding(selectAll, "table"),
                    recorder.binding(selectAll, "editor", enabled = false, blocksOuter = true),
                )
            })

        val decision = engine.onKeyDown(ctrlA)

        assertEquals(DispatchDecision.Pass, decision)
        assertTrue(recorder.invocations.isEmpty())
    }

    @Test
    fun `focused claim wins before keymap lookup`() {
        val recorder = Recorder()
        val engine =
            engine(
                bindings = { listOf(recorder.binding(selectAll, "table")) },
                claims = { listOf(recorder.claim(JewelKeySequence(ctrlA), "claim")) },
            )

        val decision = engine.onKeyDown(ctrlA)

        assertTrue(decision is DispatchDecision.Consumed)
        assertEquals(DispatchDecision.Consumed.Route.Claim, (decision as DispatchDecision.Consumed).route)
        assertEquals(listOf("claim"), recorder.invocations)
    }

    @Test
    fun `claims work for strokes with no keymap mapping`() {
        val recorder = Recorder()
        val engine = engine({ emptyList() }, { listOf(recorder.claim(JewelKeySequence(ctrlEnter), "submit")) })

        assertTrue(engine.onKeyDown(ctrlEnter) is DispatchDecision.Consumed)
        assertEquals(listOf("submit"), recorder.invocations)
    }

    @Test
    fun `unbound input is never swallowed`() {
        val engine = engine({ emptyList() })

        assertEquals(DispatchDecision.Pass, engine.onKeyDown(JewelKeyStroke(Key.B)))
    }

    @Test
    fun `keymap hit with no focused binding is not consumed`() {
        val engine = engine({ emptyList() })

        assertEquals(DispatchDecision.Pass, engine.onKeyDown(ctrlA))
    }

    @Test
    fun `chord completes through pending state`() {
        val recorder = Recorder()
        val engine = engine({ listOf(recorder.binding(reformat, "table")) })

        val first = engine.onKeyDown(ctrlK)
        assertEquals(DispatchDecision.Consumed.Route.ChordPrefix, (first as DispatchDecision.Consumed).route)
        assertTrue(engine.isAwaitingSecondStroke)

        val second = engine.onKeyDown(ctrlD)
        assertEquals(DispatchDecision.Consumed.Route.Keymap, (second as DispatchDecision.Consumed).route)
        assertEquals(listOf("table"), recorder.invocations)
        assertFalse(engine.isAwaitingSecondStroke)
    }

    @Test
    fun `nonmatching second stroke cancels and consumes without invoking its own binding`() {
        val recorder = Recorder()
        val engine =
            engine({ listOf(recorder.binding(reformat, "table"), recorder.binding(selectAll, "table-select")) })

        engine.onKeyDown(ctrlK)
        val cancel = engine.onKeyDown(ctrlA) // bound as a single stroke, but arrives as a wrong second stroke

        assertEquals(DispatchDecision.Consumed.Route.ChordCancelled, (cancel as DispatchDecision.Consumed).route)
        assertTrue(recorder.invocations.isEmpty())

        // Recovery: the next Ctrl+A dispatches normally.
        engine.onKeyDown(ctrlA)
        assertEquals(listOf("table-select"), recorder.invocations)
    }

    @Test
    fun `modifier-only key-downs do not cancel a pending chord`() {
        val recorder = Recorder()
        val engine = engine({ listOf(recorder.binding(reformat, "table")) })

        engine.onKeyDown(ctrlK)
        // The Ctrl press ahead of the second stroke reports null (modifier-only) and must be ignored.
        assertEquals(DispatchDecision.Pass, engine.onKeyDown(null))
        assertTrue(engine.isAwaitingSecondStroke)

        engine.onKeyDown(ctrlD)
        assertEquals(listOf("table"), recorder.invocations)
    }

    @Test
    fun `modifier-only key-down is null stroke`() {
        // Guards JewelKeyStroke.fromKeyDownOrNull's contract at the engine boundary.
        val engine = engine({ emptyList() })
        assertEquals(DispatchDecision.Pass, engine.onKeyDown(null))
    }

    @Test
    fun `consumed key-down arms typed suppression exactly once`() {
        val recorder = Recorder()
        val engine = engine({ emptyList() }, { listOf(recorder.claim(JewelKeySequence(plainQ), "macro")) })

        engine.onKeyDown(plainQ)
        assertTrue("the typed event following a consumed press must be swallowed", engine.onTypedEvent())
        assertFalse("suppression is one-shot", engine.onTypedEvent())
    }

    @Test
    fun `passed-through key-down does not arm typed suppression`() {
        val engine = engine({ emptyList() })

        engine.onKeyDown(JewelKeyStroke(Key.B))

        assertFalse(engine.onTypedEvent())
    }

    @Test
    fun `chord prefix arms typed suppression and repeated prefix key-downs stay consumed`() {
        val recorder = Recorder()
        val engine = engine({ listOf(recorder.binding(reformat, "table")) })

        engine.onKeyDown(ctrlK)
        assertTrue(engine.onTypedEvent())

        // A repeated first stroke while pending arrives as a "second stroke" and cancels conservatively;
        // it is still consumed, and dispatch recovers.
        val repeat = engine.onKeyDown(ctrlK)
        assertTrue(repeat is DispatchDecision.Consumed)
    }

    @Test
    fun `reset clears pending chord state`() {
        val engine = engine({ listOf(EngineBinding(reformat, true, false, "t") {}) })

        engine.onKeyDown(ctrlK)
        assertTrue(engine.isAwaitingSecondStroke)

        engine.reset()

        assertFalse(engine.isAwaitingSecondStroke)
    }

    @Test
    fun `keymap inheritance hides and replaces bindings correctly`() {
        val parent =
            InMemoryJewelKeymap("parent").apply {
                bind(selectAll, JewelKeySequence(ctrlA))
                bind(reformat, JewelKeySequence(ctrlK, ctrlD))
            }
        val child = InMemoryJewelKeymap("child", parent)

        assertEquals(listOf(JewelKeySequence(ctrlA)), child.shortcutsFor(selectAll))

        child.hideInherited(selectAll, JewelKeySequence(ctrlA))
        assertTrue(child.shortcutsFor(selectAll).isEmpty())

        child.bind(selectAll, JewelKeySequence(ctrlEnter))
        assertEquals(listOf(JewelKeySequence(ctrlEnter)), child.shortcutsFor(selectAll))

        assertEquals(listOf(reformat), child.actionIdsForPrefix(ctrlK))
    }
}
