// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.jetbrains.jewel.foundation.actionSystem.provideData
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A standalone stress benchmark for the reactive presentation model: many context-driven controls plus one hot, rapidly
 * updating provided datum that none of them read. It measures two costs under load — control recompositions (the
 * user-visible cost) and `update`-block evaluations (the CPU cost of re-resolving) — and contrasts the reactive model
 * with the pull-based scheduler model it replaces standalone.
 *
 * The headline: on the reactive path a hot datum no control reads recomputes nothing and evaluates almost no update
 * blocks, with no manual invalidation. On the scheduler path, keeping the same controls fresh requires an
 * `invalidate()` per datum tick, and each one re-resolves every demanded control — every control re-runs every
 * binding's update block, the O(controls x bindings) blow-up per pass that this change removes.
 *
 * Numbers are printed and asserted as ratios (not absolute timings), so the test stays meaningful across machines.
 */
internal class ReactivePresentationBenchmarkTest {
    @JvmField @Rule internal val rule: ComposeContentTestRule = createComposeRule()

    private val controlCount = 24
    private val hotTicks = 30

    private val actions = List(controlCount) { JewelAction(JewelActionId("bench.action.$it"), "Action $it") }
    private val keys = List(controlCount) { ActionContextKey.create<Boolean>("bench.key.$it") }
    private val states = List(controlCount) { mutableIntStateOf(0) }
    private val hotKey = ActionContextKey.create<Boolean>("bench.hot")
    private val hot = mutableIntStateOf(0)

    private val keymap = InMemoryJewelKeymap("bench")
    private val surfaceFocus = FocusRequester()

    /** Counts every `update`-block evaluation across all controls — the re-resolve cost. */
    private val evals = AtomicInteger(0)

    /** Counts every control recomposition — the user-visible cost. */
    private val recompositions = AtomicInteger(0)

    @Volatile private var lastEnabled = false

    @Composable
    private fun BenchControl(host: JewelShortcutHostState, action: JewelAction) {
        val presentation by action.collectPresentationAsState(host)
        // Consume the value so the control genuinely subscribes, exactly as a real control that renders it would.
        lastEnabled = presentation.enabled
        recompositions.incrementAndGet()
    }

    /**
     * Builds one focused surface carrying: an outermost hot provider, N key providers, and N context-driven bindings.
     */
    private fun Modifier.benchSurface(): Modifier {
        var m = this
        // Hot is the OUTERMOST provider, so the nearest-first live lookup never reaches it while resolving a key —
        // no control subscribes to the hot datum.
        m = m.provideData { set(hotKey.name, hot.intValue % 2 == 0) }
        for (i in 0 until controlCount) {
            val key = keys[i]
            val state = states[i]
            m = m.provideData { set(key.name, state.intValue % 2 == 0) }
        }
        for (i in 0 until controlCount) {
            val key = keys[i]
            m =
                m.shortcut(
                    actions[i],
                    update = {
                        evals.incrementAndGet()
                        enabled = context[key] == true
                    },
                ) {}
        }
        return m.focusRequester(surfaceFocus).focusable()
    }

    private fun focusSurface() {
        runBlocking {
            rule.runOnIdle { surfaceFocus.requestFocus() }
            rule.awaitIdle()
        }
    }

    @Test
    fun `a hot datum no control reads costs almost nothing on the reactive path, and much more on the scheduler path`() {
        // -------- Reactive path (the default) --------
        val reactiveHost = JewelShortcutHostState { keymap }
        rule.setContent {
            Box(reactiveHost.resolverRootModifier) {
                Box(Modifier.benchSurface()) { for (action in actions) BenchControl(reactiveHost, action) }
            }
        }
        focusSurface()
        rule.waitForIdle()

        val reactiveEvalsBefore = evals.get()
        val reactiveRecompBefore = recompositions.get()
        repeat(hotTicks) {
            rule.runOnIdle { hot.intValue++ }
            rule.waitForIdle()
        }
        val reactiveEvals = evals.get() - reactiveEvalsBefore
        val reactiveRecomps = recompositions.get() - reactiveRecompBefore

        // -------- Scheduler path (what standalone used before) --------
        evals.set(0)
        recompositions.set(0)
        val schedulerHost = JewelShortcutHostState { keymap }.also { it.reactivePresentation = false }
        rule.setContent {
            Box(schedulerHost.resolverRootModifier) {
                Box(Modifier.benchSurface()) { for (action in actions) BenchControl(schedulerHost, action) }
            }
        }
        focusSurface()
        rule.waitForIdle()

        val schedulerEvalsBefore = evals.get()
        repeat(hotTicks) {
            rule.runOnIdle {
                hot.intValue++
                // The scheduler path is demand-driven: without an explicit invalidate the controls would render stale,
                // so the app must nudge it on every datum change — and each nudge re-resolves every demanded control.
                schedulerHost.presentations.invalidate()
            }
            rule.waitForIdle()
        }
        val schedulerEvals = evals.get() - schedulerEvalsBefore

        println(
            "BENCH controls=$controlCount hotTicks=$hotTicks | " +
                "reactive: recompositions=$reactiveRecomps evals=$reactiveEvals | " +
                "scheduler: evals=$schedulerEvals"
        )

        // The reactive path recomposes nothing: no control reads the hot datum.
        assertTrue(
            "reactive control recompositions on unrelated hot churn should be ~0, was $reactiveRecomps",
            reactiveRecomps == 0,
        )
        // And it evaluates essentially no update blocks (no derivation depends on the hot datum).
        assertTrue(
            "reactive update-block evals on unrelated hot churn should be tiny, was $reactiveEvals",
            reactiveEvals <= controlCount,
        )
        // The scheduler path pays the re-resolve blow-up: each invalidate re-runs every control's update block, so it
        // is
        // orders of magnitude more work for the very same hot churn.
        assertTrue(
            "scheduler evals ($schedulerEvals) should dwarf reactive evals ($reactiveEvals)",
            schedulerEvals > reactiveEvals * 20 + 1000,
        )
    }
}
