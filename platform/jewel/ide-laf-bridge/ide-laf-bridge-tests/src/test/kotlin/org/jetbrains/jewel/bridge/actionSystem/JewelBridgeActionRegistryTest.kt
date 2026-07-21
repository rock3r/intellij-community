// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.actionSystem

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionDefinition
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the attach-or-register lifecycle of [JewelBridgeActionRegistry] against a mocked [ActionManager]: declared
 * (plugin.xml) IDs are attached to and never unregistered; unowned IDs get a runtime action whose lifetime is the
 * reference-counted registration handle; foreign owners are rejected with the mapping hint.
 */
internal class JewelBridgeActionRegistryTest {
    private val actionId = JewelActionId("test.jewel.bridge.action")
    private val definition = JewelActionDefinition(JewelAction(actionId, "Test Action"))

    @Test
    fun `declared bridge action is attached, not re-registered, and closing never unregisters it`() {
        val declared = JewelActionBridgeAction()
        val actionManager =
            mockk<ActionManager>(relaxed = true) { every { getAction(actionId.value) } returns declared }
        val registry = JewelBridgeActionRegistry(actionManager)

        val registration = registry.register(definition)
        assertEquals(definition, registry.definition(actionId))
        verify(exactly = 0) { actionManager.registerAction(any(), any()) }

        registration.close()
        // Closing drops the explicit registration, but the ID stays resolvable: the registry adopts actions the
        // IDE declares, which is what lets a Jewel control bind an action it never registered. What must not
        // happen is the registry unregistering an action it does not own.
        val adopted = registry.definition(actionId)
        assertNotNull(adopted)
        assertNotEquals(definition, adopted)
        verify(exactly = 0) { actionManager.unregisterAction(any<String>()) }
    }

    @Test
    fun `unowned ID registers a runtime action and the last close unregisters it`() {
        val actionManager = mockk<ActionManager>(relaxed = true) { every { getAction(actionId.value) } returns null }
        val registry = JewelBridgeActionRegistry(actionManager)

        val first = registry.register(definition)
        val second = registry.register(definition)
        verify(exactly = 1) { actionManager.registerAction(actionId.value, any<JewelActionBridgeAction>()) }

        first.close()
        first.close() // closing is idempotent; the second close must not decrement again
        assertNotNull(registry.definition(actionId))
        verify(exactly = 0) { actionManager.unregisterAction(any<String>()) }

        second.close()
        assertNull(registry.definition(actionId))
        verify(exactly = 1) { actionManager.unregisterAction(actionId.value) }
    }

    @Test
    fun `runtime registration always uses the non dumb-aware base class`() {
        val actionManager = mockk<ActionManager>(relaxed = true) { every { getAction(actionId.value) } returns null }
        val registered = mutableListOf<AnAction>()
        every { actionManager.registerAction(actionId.value, capture(registered)) } returns Unit

        JewelBridgeActionRegistry(actionManager).register(definition)
        assertEquals(JewelActionBridgeAction::class.java, registered.single().javaClass)
    }

    @Test
    fun `differing definition for a registered ID fails`() {
        val actionManager = mockk<ActionManager>(relaxed = true) { every { getAction(actionId.value) } returns null }
        val registry = JewelBridgeActionRegistry(actionManager)
        registry.register(definition)

        assertThrows(IllegalStateException::class.java) {
            registry.register(JewelActionDefinition(JewelAction(actionId, "Different Title")))
        }
    }

    @Test
    fun `an ID owned by a non-Jewel action is rejected towards explicit mappings`() {
        val actionManager =
            mockk<ActionManager>(relaxed = true) { every { getAction(actionId.value) } returns mockk<AnAction>() }
        val registry = JewelBridgeActionRegistry(actionManager)

        val failure = assertThrows(IllegalStateException::class.java) { registry.register(definition) }
        assertNotNull(failure.message)
        assert(failure.message!!.contains("JewelActionMappings"))
    }
}
