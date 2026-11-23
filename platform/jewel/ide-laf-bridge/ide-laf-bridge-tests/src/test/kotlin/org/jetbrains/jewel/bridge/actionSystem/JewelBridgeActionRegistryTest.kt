// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.actionSystem

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
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
import org.junit.Assert.assertTrue
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
        var registered: AnAction? = null
        val actionManager =
            mockk<ActionManager>(relaxed = true) {
                every { getAction(actionId.value) } answers { registered }
                every { registerAction(actionId.value, any()) } answers { registered = secondArg() }
                every { unregisterAction(actionId.value) } answers { registered = null }
            }
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

    @Test
    fun `a runtime action shared by two panel registries survives the first registry closing`() {
        var registered: AnAction? = null
        val actionManager = mockk<ActionManager>(relaxed = true)
        every { actionManager.getAction(actionId.value) } answers { registered }
        every { actionManager.registerAction(actionId.value, any()) } answers { registered = secondArg() }
        every { actionManager.unregisterAction(actionId.value) } answers { registered = null }

        val panelA = JewelBridgeActionRegistry(actionManager)
        val panelB = JewelBridgeActionRegistry(actionManager)
        val registrationA = panelA.register(definition)
        val registrationB = panelB.register(definition)
        assertNotNull(actionManager.getAction(actionId.value))
        verify(exactly = 1) { actionManager.registerAction(actionId.value, any<JewelActionBridgeAction>()) }

        registrationA.close()
        assertNotNull(
            "panel B still holds a registration, so the platform action must stay registered",
            actionManager.getAction(actionId.value),
        )
        verify(exactly = 0) { actionManager.unregisterAction(any<String>()) }

        registrationB.close()
        assertNull("the last close unregisters the runtime action", actionManager.getAction(actionId.value))
        verify(exactly = 1) { actionManager.unregisterAction(actionId.value) }
    }

    @Test
    fun `adoption misses are not memoized so later declared actions can still be adopted`() {
        var declared: AnAction? = null
        val actionManager = mockk<ActionManager>(relaxed = true)
        every { actionManager.getAction(actionId.value) } answers { declared }

        val registry = JewelBridgeActionRegistry(actionManager)
        assertNull(registry.definition(actionId))

        declared =
            object : AnAction() {
                init {
                    templatePresentation.setText("Later", false)
                }

                override fun actionPerformed(e: AnActionEvent) = Unit
            }
        val adopted = registry.definition(actionId)
        assertNotNull("later registered action must adopt after an earlier miss", adopted)
        assertEquals("Later", adopted?.action?.template?.text)
    }

    @Test
    fun `shortcutsFor safely degrades when ApplicationManager is absent`() {
        val actionManager = mockk<ActionManager>(relaxed = true)
        val registry = JewelBridgeActionRegistry(actionManager)
        assertTrue(registry.shortcutsFor(actionId).isEmpty())
    }

    @Test
    fun `runtime registration disables mnemonic parsing on templatePresentation text`() {
        val mnemonicId = JewelActionId("test.jewel.mnemonic.registry")
        val mnemonicDef = JewelActionDefinition(JewelAction(mnemonicId, "Find & Replace_Text"))
        var registered: AnAction? = null
        val actionManager =
            mockk<ActionManager>(relaxed = true) {
                every { getAction(mnemonicId.value) } answers { registered }
                every { registerAction(mnemonicId.value, any()) } answers { registered = secondArg() }
                every { unregisterAction(mnemonicId.value) } answers { registered = null }
            }
        val reg = JewelBridgeActionRegistry(actionManager).register(mnemonicDef)
        try {
            assertNotNull(registered)
            assertEquals("Find & Replace_Text", registered!!.templatePresentation.text)
            assertEquals(-1, registered!!.templatePresentation.displayedMnemonicIndex)
        } finally {
            reg.close()
        }
    }

    @Test
    fun `re-registering after external unregister restores the shared runtime action`() {
        val sharedId = JewelActionId("test.jewel.runtime.reattach")
        val sharedDef = JewelActionDefinition(JewelAction(sharedId, "Reattach"))
        var registered: AnAction? = null
        val actionManager =
            mockk<ActionManager>(relaxed = true) {
                every { getAction(sharedId.value) } answers { registered }
                every { registerAction(sharedId.value, any()) } answers { registered = secondArg() }
                every { unregisterAction(sharedId.value) } answers { registered = null }
            }
        val panelA = JewelBridgeActionRegistry(actionManager)
        val panelB = JewelBridgeActionRegistry(actionManager)
        val regA = panelA.register(sharedDef)
        val initialAction = registered
        assertNotNull(initialAction)

        // Simulate external teardown unregistering the action directly.
        registered = null
        val regB = panelB.register(sharedDef)
        assertTrue("re-registering must restore the shared runtime action instance", registered === initialAction)

        regA.close()
        assertTrue(registered === initialAction)
        regB.close()
        assertNull(registered)
    }

    @Test
    fun `external replacement of runtime action is neither clobbered nor unregistered on close`() {
        val replacedId = JewelActionId("test.jewel.runtime.replaced")
        val replacedDef = JewelActionDefinition(JewelAction(replacedId, "Replaced"))
        var registered: AnAction? = null
        val actionManager =
            mockk<ActionManager>(relaxed = true) {
                every { getAction(replacedId.value) } answers { registered }
                every { registerAction(replacedId.value, any()) } answers { registered = secondArg() }
                every { unregisterAction(replacedId.value) } answers { registered = null }
            }
        val panelA = JewelBridgeActionRegistry(actionManager)
        val panelB = JewelBridgeActionRegistry(actionManager)
        val regA = panelA.register(replacedDef)

        val foreignAction = mockk<AnAction>(relaxed = true)
        registered = foreignAction

        val regB = panelB.register(replacedDef)
        assertTrue("foreign occupant must not be overwritten", registered === foreignAction)

        regA.close()
        regB.close()
        assertTrue("foreign occupant must not be unregistered when Jewel handles close", registered === foreignAction)
        verify(exactly = 0) { actionManager.unregisterAction(replacedId.value) }
    }
}
