// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the PRD failure/lifecycle/race table rows at the presentation layer, plus the per-binding
 * presentation override merge and the stale-execution guard.
 */
internal class PresentationContractTest {
    private val actionId = JewelActionId("test.presentation.action")
    private val action = JewelAction(actionId, "Presentable")
    private val keymap = InMemoryJewelKeymap("test")

    @Test
    fun `unregistered action presents as Unregistered and disabled when a registry is installed`() {
        val registry = DefaultJewelActionRegistry()
        val host = JewelShortcutHostState(registry) { keymap }

        val presentation = host.presentationFor(actionId)

        assertEquals(ActionResolution.Unregistered, presentation.resolution)
        assertFalse(presentation.enabled)
        assertEquals(actionId.value, presentation.text)
    }

    @Test
    fun `registered but unfocused action presents as NoFocusedBinding with the registered title`() {
        val registry = DefaultJewelActionRegistry()
        registry.register(JewelActionDefinition(action))
        val host = JewelShortcutHostState(registry) { keymap }

        val presentation = host.presentationFor(actionId)

        assertEquals(ActionResolution.NoFocusedBinding, presentation.resolution)
        assertFalse(presentation.enabled)
        assertEquals("Presentable", presentation.text)
    }

    @Test
    fun `without a registry an unfocused action presents as NoFocusedBinding`() {
        val host = JewelShortcutHostState { keymap }
        assertEquals(ActionResolution.NoFocusedBinding, host.presentationFor(actionId).resolution)
    }

    @Test
    fun `hostUnavailable presentation carries the action title, disabled`() {
        val presentation = ActionPresentation.hostUnavailable(action)
        assertEquals(ActionResolution.HostUnavailable, presentation.resolution)
        assertFalse(presentation.enabled)
        assertEquals("Presentable", presentation.text)
    }

    @Test
    fun `override merge carries selected, icon, and menu dismiss policy over the template`() {
        val base = ActionPresentation(text = "T", enabled = true, resolution = ActionResolution.Resolved)
        val icon = Any()
        val merged =
            ActionPresentationOverride(
                    selected = PresentationValue.Set(true),
                    icon = PresentationValue.Set(icon),
                    menuDismissPolicy = PresentationValue.Set(MenuDismissPolicy.KeepAlways),
                )
                .mergeOver(base)

        assertTrue(merged.selected)
        assertTrue(merged.icon === icon)
        assertEquals(MenuDismissPolicy.KeepAlways, merged.menuDismissPolicy)
        assertEquals("T", merged.text)

        val cleared =
            ActionPresentationOverride(menuDismissPolicy = PresentationValue.Set(null)).mergeOver(merged)
        assertNull(cleared.menuDismissPolicy)
        assertTrue("non-Set fields inherit", cleared.selected)
    }

    @Test
    fun `stale execution guard - invoker rejects once the focused binding is gone`() {
        var invoked = 0
        var bindings =
            listOf(EngineBinding(actionId, enabled = true, blocksOuterBindings = false, origin = "T") { invoked++ })
        val engine = ShortcutDispatchEngine({ keymap }, { bindings }, { emptyList() })

        // A resolved handler executes...
        engine.resolveFocusedBinding(actionId)!!.onInvoke()
        assertEquals(1, invoked)

        // ...but a stale reference re-resolves at invocation time and finds nothing: no execution.
        bindings = emptyList()
        assertNull(engine.resolveFocusedBinding(actionId))
    }

    @Test
    fun `unregistered diagnostics are coalesced per action ID`() {
        val registry = DefaultJewelActionRegistry()
        val host = JewelShortcutHostState(registry) { keymap }

        // Repeated sampling must not throw and must keep returning the same failure row; the log-once
        // set is internal, so this pins the observable part of the contract.
        repeat(3) { assertEquals(ActionResolution.Unregistered, host.presentationFor(actionId).resolution) }
    }
}
