// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.actionSystem

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.IdeActions
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import javax.swing.JPanel
import org.jetbrains.jewel.foundation.shortcut.ActionDispatchRejection
import org.jetbrains.jewel.foundation.shortcut.ActionDispatchResult
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.jetbrains.jewel.foundation.shortcut.JewelActions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Pins [JewelActionMappings]' explicit `MappedIdeAction` routing and [JewelBridgeActionInvoker]'s dispatch-acceptance
 * contract against a mocked [ActionManager].
 */
internal class JewelBridgeActionInvokerTest {
    private val hostComponent = JPanel()

    @Before
    fun setUp() {
        JewelActionMappings.clearForTests()
    }

    @After
    fun tearDown() {
        JewelActionMappings.clearForTests()
    }

    @Test
    fun `standard mappings install idempotently and never clobber an explicit override`() {
        val customTarget = "MyCustomCopy"
        val actionManager = mockk<ActionManager> { every { getAction(customTarget) } returns mockk<AnAction>() }
        mockkStatic(ActionManager::class)
        try {
            every { ActionManager.getInstance() } returns actionManager

            JewelActionMappings.map(JewelActions.Copy.id, customTarget)
            JewelActionMappings.installStandardMappings()
            JewelActionMappings.installStandardMappings()

            assertEquals(customTarget, JewelActionMappings.ideActionIdFor(JewelActions.Copy.id))
            assertEquals(IdeActions.ACTION_CUT, JewelActionMappings.ideActionIdFor(JewelActions.Cut.id))
        } finally {
            unmockkStatic(ActionManager::class)
        }
    }

    @Test
    fun `unmapped action IDs resolve to no mapping`() {
        JewelActionMappings.installStandardMappings()
        assertNull(JewelActionMappings.ideActionIdFor(JewelActionId("test.unmapped")))
    }

    @Test
    fun `mapped ID routes to the mapped platform action through tryToExecute`() {
        JewelActionMappings.installStandardMappings()
        val copyAction = mockk<AnAction>()
        val actionManager =
            mockk<ActionManager>(relaxed = true) { every { getAction(IdeActions.ACTION_COPY) } returns copyAction }

        val result = JewelBridgeActionInvoker(hostComponent, actionManager).invoke(JewelActions.Copy)

        assertEquals(ActionDispatchResult.Dispatched, result)
        verify(exactly = 1) { actionManager.tryToExecute(copyAction, null, hostComponent, any(), true) }
    }

    @Test
    fun `unmapped ID routes to the action registered under the Jewel ID itself`() {
        val jewelAction = JewelAction(JewelActionId("test.jewel.owned"), "Owned")
        val bridgeAction = JewelActionBridgeAction()
        val actionManager =
            mockk<ActionManager>(relaxed = true) { every { getAction(jewelAction.id.value) } returns bridgeAction }

        val result = JewelBridgeActionInvoker(hostComponent, actionManager).invoke(jewelAction)

        assertEquals(ActionDispatchResult.Dispatched, result)
        verify(exactly = 1) { actionManager.tryToExecute(bridgeAction, null, hostComponent, any(), true) }
    }

    @Test
    fun `unknown target rejects as Unregistered without touching execution`() {
        val jewelAction = JewelAction(JewelActionId("test.jewel.unknown"), "Unknown")
        val actionManager =
            mockk<ActionManager>(relaxed = true) { every { getAction(jewelAction.id.value) } returns null }

        val result = JewelBridgeActionInvoker(hostComponent, actionManager).invoke(jewelAction)

        assertEquals(ActionDispatchResult.Rejected(ActionDispatchRejection.Unregistered), result)
        verify(exactly = 0) { actionManager.tryToExecute(any(), any(), any(), any(), any()) }
    }
}
