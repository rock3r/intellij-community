// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.actionSystem

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.keymap.Keymap
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.openapi.keymap.impl.IdeKeyEventDispatcher
import com.intellij.openapi.keymap.impl.KeymapImpl
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.DefaultKeyboardFocusManager
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JTextField
import javax.swing.KeyStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.jetbrains.jewel.bridge.JewelComposePanelWrapper
import org.jetbrains.jewel.foundation.shortcut.ActionInvocation
import org.jetbrains.jewel.foundation.shortcut.ActionTrigger
import org.jetbrains.jewel.foundation.shortcut.InMemoryJewelKeymap
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionDefinition
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.jetbrains.jewel.foundation.shortcut.JewelShortcutHostState
import org.jetbrains.jewel.foundation.shortcut.shortcut
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end bridge action dispatch against the real platform action system: a real [ActionManager], a real
 * [KeymapImpl] driving a real [IdeKeyEventDispatcher], and a real Compose composition providing the focused
 * `Modifier.shortcut` bindings — only rendering and AWT focus are stubbed (the Compose surface is headless; a stub
 * [KeyboardFocusManager] plays the role of the skiko focus owner inside the wrapper).
 *
 * Covers registration parity (declared vs runtime), custom-keymap rebinding without recomposition, manual invocation
 * parity, and focused-subtree isolation across two panels — plus the event contract (exactly one Jewel event per
 * bridge-owned invocation).
 */
internal class JewelBridgeActionIntegrationTest {
    private companion object {
        @ClassRule @JvmField val application = ApplicationRule()

        const val KEYMAP_NAME = "JewelBridgeActionIntegrationTestKeymap"
        const val ACTION_ID = "test.jewel.integration.action"

        val STROKE: KeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK)
        val REBOUND_STROKE: KeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK)
    }

    @JvmField @Rule internal val compose: ComposeContentTestRule = createComposeRule()

    private class StubFocusManager(private val owner: Component) : DefaultKeyboardFocusManager() {
        override fun getFocusOwner(): Component = owner
    }

    private lateinit var keymap: KeymapImpl
    private lateinit var savedKeymap: Keymap
    private lateinit var testDisposable: com.intellij.openapi.Disposable

    /** Stands in for the AWT focus owner that a real focused window would report. */
    private var awtFocusOwner: Component? = null

    private lateinit var wrapperA: JewelComposePanelWrapper
    private lateinit var wrapperB: JewelComposePanelWrapper
    private lateinit var innerA: JTextField
    private lateinit var innerB: JTextField

    private val stateA = JewelShortcutHostState { InMemoryJewelKeymap("bridge-claims-only") }
    private val stateB = JewelShortcutHostState { InMemoryJewelKeymap("bridge-claims-only") }

    private val focusA = FocusRequester()
    private val focusB = FocusRequester()

    private var countA = 0
    private var countB = 0

    private val jewelAction = JewelAction(JewelActionId(ACTION_ID), "Integration Action")

    @Before
    fun setUp() {
        testDisposable = Disposer.newDisposable("JewelBridgeActionIntegrationTest")

        // dispatchKeyEvent silently returns false when the DataManager service was never instantiated.
        // In tests that service is a HeadlessDataManager, which never discovers a focused component on
        // its own (there is no focused window); provide the stubbed focus owner as the context component
        // so the bridge action's host resolution sees what production dispatch would see.
        // What production provides through the component-based context: the wrapper in the focus
        // owner's ancestry sinks the host state (pinned by `the wrapper sinks its host state into the
        // data context`); CONTEXT_COMPONENT itself is a UI-snapshot key a headless context never gets.
        val dataManager = DataManager.getInstance() as HeadlessDataManager
        @Suppress("DEPRECATION")
        dataManager.setTestDataProvider(
            { dataId ->
                when {
                    JEWEL_SHORTCUT_HOST_STATE.`is`(dataId) -> findJewelShortcutHost(awtFocusOwner)
                    PlatformCoreDataKeys.CONTEXT_COMPONENT.`is`(dataId) -> awtFocusOwner
                    else -> null
                }
            },
            testDisposable,
        )

        keymap = KeymapImpl()
        keymap.name = KEYMAP_NAME
        KeymapManagerEx.getInstanceEx().schemeManager.addScheme(keymap, false)
        savedKeymap = KeymapManagerEx.getInstanceEx().activeKeymap
        KeymapManagerEx.getInstanceEx().activeKeymap = keymap

        runInEdtAndWait {
            wrapperA = JewelComposePanelWrapper(focusOnClickInside = false)
            wrapperB = JewelComposePanelWrapper(focusOnClickInside = false)
            innerA = JTextField()
            innerB = JTextField()
            wrapperA.composePanel.add(innerA)
            wrapperB.composePanel.add(innerB)
            wrapperA.shortcutHostState = stateA
            wrapperB.shortcutHostState = stateB
        }

        compose.setContent {
            Row {
                Box(stateA.resolverRootModifier) {
                    Box(Modifier.shortcut(jewelAction) { countA++ }.focusRequester(focusA).focusable())
                }
                Box(stateB.resolverRootModifier) {
                    Box(Modifier.shortcut(jewelAction) { countB++ }.focusRequester(focusB).focusable())
                }
            }
        }
    }

    @After
    fun tearDown() {
        Disposer.dispose(testDisposable)
        KeyboardFocusManager.setCurrentKeyboardFocusManager(null)
        ActionManager.getInstance().getAction(ACTION_ID)?.let {
            ActionManager.getInstance().unregisterAction(ACTION_ID)
        }
        KeymapManagerEx.getInstanceEx().activeKeymap = savedKeymap
        KeymapManagerEx.getInstanceEx().schemeManager.removeScheme(keymap)
    }

    private fun focusComposeNode(requester: FocusRequester) {
        runBlocking {
            compose.runOnIdle { requester.requestFocus() }
            compose.awaitIdle()
        }
    }

    private fun awtFocusOn(inner: Component) {
        awtFocusOwner = inner
        KeyboardFocusManager.setCurrentKeyboardFocusManager(StubFocusManager(inner))
    }

    private fun dispatch(source: Component, stroke: KeyStroke): Boolean {
        var dispatched = false
        runInEdtAndWait {
            val event =
                KeyEvent(
                    source,
                    KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(),
                    stroke.modifiers,
                    stroke.keyCode,
                    KeyEvent.CHAR_UNDEFINED,
                )
            dispatched = IdeKeyEventDispatcher(null).dispatchKeyEvent(event)
        }
        return dispatched
    }

    private fun registerDeclared(): JewelActionBridgeAction {
        val action = JewelActionBridgeAction()
        ActionManager.getInstance().registerAction(ACTION_ID, action)
        keymap.addShortcut(ACTION_ID, KeyboardShortcut(STROKE, null))
        return action
    }

    @Test
    fun `declared bridge action dispatches through the IDE keymap to the focused Jewel handler`() {
        registerDeclared()
        focusComposeNode(focusA)
        awtFocusOn(innerA)

        assertTrue(dispatch(innerA, STROKE))
        assertEquals(1, countA)
        assertEquals(0, countB)
    }

    @Test
    fun `runtime-registered bridge action behaves identically to a declared one`() {
        val registry = JewelBridgeActionRegistry()
        val registration = registry.register(JewelActionDefinition(jewelAction))
        keymap.addShortcut(ACTION_ID, KeyboardShortcut(STROKE, null))
        focusComposeNode(focusA)
        awtFocusOn(innerA)

        assertTrue(dispatch(innerA, STROKE))
        assertEquals(1, countA)

        registration.close()
        assertFalse("unregistered action must no longer dispatch", dispatch(innerA, STROKE))
        assertEquals(1, countA)
    }

    @Test
    fun `custom keymap rebinding takes effect without recomposition`() {
        registerDeclared()
        focusComposeNode(focusA)
        awtFocusOn(innerA)

        assertTrue(dispatch(innerA, STROKE))
        assertEquals(1, countA)

        keymap.removeShortcut(ACTION_ID, KeyboardShortcut(STROKE, null))
        keymap.addShortcut(ACTION_ID, KeyboardShortcut(REBOUND_STROKE, null))

        assertFalse("unbound stroke must fall through", dispatch(innerA, STROKE))
        assertEquals(1, countA)
        assertTrue(dispatch(innerA, REBOUND_STROKE))
        assertEquals(2, countA)
    }

    @Test
    fun `focused-subtree isolation across two panels follows AWT and Compose focus`() {
        registerDeclared()

        focusComposeNode(focusA)
        awtFocusOn(innerA)
        assertTrue(dispatch(innerA, STROKE))
        assertEquals(1, countA)
        assertEquals(0, countB)

        focusComposeNode(focusB)
        awtFocusOn(innerB)
        assertTrue(dispatch(innerB, STROKE))
        assertEquals(1, countA)
        assertEquals(1, countB)
    }

    @Test
    fun `manual invocation through the bridge invoker matches keyboard dispatch`() {
        registerDeclared()
        focusComposeNode(focusA)
        awtFocusOn(innerA)

        runInEdtAndWait { JewelBridgeActionInvoker(wrapperA).invoke(jewelAction) }
        // tryToExecute defers the actual perform to the EDT event queue.
        runInEdtAndWait { UIUtil.dispatchAllInvocationEvents() }

        assertEquals(1, countA)
        assertEquals(0, countB)
    }

    @Test
    fun `bridge-owned invocations emit exactly one Jewel event, on the owning host`() {
        registerDeclared()
        focusComposeNode(focusA)
        awtFocusOn(innerA)

        val eventsA = mutableListOf<ActionInvocation>()
        val eventsB = mutableListOf<ActionInvocation>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        stateA.events.invocations.onEach { eventsA += it }.launchIn(scope)
        stateB.events.invocations.onEach { eventsB += it }.launchIn(scope)
        try {
            assertTrue(dispatch(innerA, STROKE))

            assertEquals(1, eventsA.size)
            assertEquals(JewelActionId(ACTION_ID), eventsA.single().actionId)
            assertTrue(eventsA.single().trigger is ActionTrigger.Keyboard)
            assertTrue(eventsB.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `bridge action declares modal-context enablement`() {
        assertTrue(JewelActionBridgeAction().isEnabledInModalContext)
        assertTrue(DumbAwareJewelActionBridgeAction().isEnabledInModalContext)
    }

    @Test
    fun `the wrapper sinks its host state into the data context`() {
        val sink = TestDataSink()
        wrapperA.uiDataSnapshot(sink)
        assertTrue(sink.get<JewelShortcutHostState>(JEWEL_SHORTCUT_HOST_STATE.name) === stateA)
    }

    @Test
    fun `an IDE-declared action resolves its template presentation through the bridge registry`() {
        // A control bound to an action the IDE already declares must render that action's authored text,
        // description and icon. Before the bridge host carried a registry, the sample had no template at all:
        // controls fell back to the binding's origin text and drew no icon, while still dispatching correctly —
        // a silent, visual-only failure.
        val actionId = "jewel.test.declaredPresentation"
        val declared =
            object : AnAction("Declared Save", "Saves the declared way", AllIcons.Actions.MenuSaveall) {
                override fun actionPerformed(e: AnActionEvent) = Unit
            }
        val actionManager = ActionManager.getInstance()
        actionManager.registerAction(actionId, declared)
        try {
            val registry = JewelBridgeActionRegistry(actionManager)

            val definition = registry.definition(JewelActionId(actionId))

            assertNotNull("the declared action should be adopted", definition)
            val template = definition!!.action.template
            assertEquals("Declared Save", template.text)
            assertEquals("Saves the declared way", template.description)
            assertNotNull("the platform icon should cross as a descriptor", template.icon)
        } finally {
            actionManager.unregisterAction(actionId)
        }
    }

    @Test
    fun `an unknown action ID is not adopted and does not resolve`() {
        val registry = JewelBridgeActionRegistry(ActionManager.getInstance())
        assertNull(registry.definition(JewelActionId("jewel.test.doesNotExistAnywhere")))
    }
}
