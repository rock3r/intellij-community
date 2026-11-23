// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataMap
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DataSnapshotProvider
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.util.Disposer
import com.intellij.platform.icons.IconManager
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.jewel.bridge.actionSystem.ComponentDataProviderBridge
import org.jetbrains.jewel.bridge.actionSystem.JewelActionBridgeAction
import org.jetbrains.jewel.bridge.component.JBPopupRenderer
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.LocalComponent
import org.jetbrains.jewel.foundation.actionSystem.provideData
import org.jetbrains.jewel.foundation.shortcut.ActionContextKey
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.jetbrains.jewel.foundation.shortcut.JewelShortcutHostState
import org.jetbrains.jewel.foundation.shortcut.LocalJewelShortcutHost
import org.jetbrains.jewel.foundation.shortcut.shortcut
import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.LocalPopupRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

/** Verifies [ShortcutHostBridge]'s Frame-1 configuration and presentation-refresh contracts under headless Compose. */
internal class ShortcutHostBridgeTest {
    private companion object {
        @ClassRule @JvmField val application = ApplicationRule()

        @BeforeClass
        @JvmStatic
        fun activateIconManager() {
            val manager =
                Class.forName("com.intellij.platform.icons.impl.intellij.IntelliJIconManager")
                    .getDeclaredConstructor()
                    .newInstance() as IconManager
            IconManager.activate(manager)
        }
    }

    @Suppress("NonExtendableApiUsage", "OverrideOnly")
    private class MapDataSink : DataSink {
        val values = LinkedHashMap<String, Any?>()

        override fun <T : Any> set(key: DataKey<T>, data: T?) {
            if (data != null) {
                values[key.name] = data
            }
        }

        override fun <T : Any> setNull(key: DataKey<T>) {
            values.remove(key.name)
        }

        override fun <T : Any> lazy(key: DataKey<T>, data: () -> T?) {
            set(key, data())
        }

        override fun <T : Any> lazyNull(key: DataKey<T>) {
            values.remove(key.name)
        }

        override fun <T : Any> lazyValue(key: DataKey<T>, data: (DataMap) -> T?) = Unit

        override fun uiDataSnapshot(provider: UiDataProvider) {
            provider.uiDataSnapshot(this)
        }

        override fun uiDataSnapshot(provider: DataProvider) = Unit

        override fun dataSnapshot(provider: DataSnapshotProvider) {
            provider.dataSnapshot(this)
        }
    }

    @JvmField @Rule internal val compose: ComposeContentTestRule = createComposeRule()

    private lateinit var testDisposable: Disposable
    private lateinit var wrapper: JewelComposePanelWrapper

    @Before
    fun setUp() {
        testDisposable = Disposer.newDisposable("ShortcutHostBridgeTest")
        runInEdtAndWait { wrapper = JewelComposePanelWrapper(focusOnClickInside = false) }
        val dataManager = DataManager.getInstance() as HeadlessDataManager
        @Suppress("DEPRECATION")
        dataManager.setTestDataProvider(
            { dataId ->
                val sink = MapDataSink()
                wrapper.uiDataSnapshot(sink)
                sink.values[dataId]
            },
            testDisposable,
        )
    }

    @After
    fun tearDown() {
        Disposer.dispose(testDisposable)
    }

    private fun control(tag: String): SemanticsNodeInteraction =
        compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasClickAction())

    @Test
    fun `ShortcutHostBridge disables reactivePresentation before Frame 1 composition and updates on invalidate`() {
        val actionId = "jewel.test.bridge.frame1"
        val action = JewelAction(JewelActionId(actionId), "Frame 1 Action")
        val focus = FocusRequester()
        var bindingEnabled by mutableStateOf(true)
        var frame1Host: JewelShortcutHostState? = null
        var frame1ReactivePresentation: Boolean? = null

        val declared =
            JewelActionBridgeAction().apply {
                templatePresentation.text = "Frame 1 Action"
                templatePresentation.description = "Frame 1"
                templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
            }
        val actionManager = ActionManager.getInstance()
        actionManager.registerAction(actionId, declared)
        try {
            compose.setContent {
                SwingBridgeTheme {
                    CompositionLocalProvider(
                        LocalComponent provides wrapper,
                        LocalPopupRenderer provides JBPopupRenderer,
                    ) {
                        ShortcutHostBridge(wrapper) {
                            ComponentDataProviderBridge(wrapper) {
                                val host = LocalJewelShortcutHost.current
                                if (frame1Host == null && host != null) {
                                    frame1Host = host
                                    frame1ReactivePresentation = host.reactivePresentation
                                }
                                Box(
                                    Modifier.shortcut(action, enabled = bindingEnabled) {}
                                        .focusRequester(focus)
                                        .focusable()
                                ) {
                                    ActionButton(action, modifier = Modifier.testTag("btn"))
                                }
                            }
                        }
                    }
                }
            }

            compose.waitForIdle()
            val host = frame1Host
            assertNotNull("ShortcutHostBridge must provide LocalJewelShortcutHost on Frame 1", host)
            assertEquals(
                "reactivePresentation must already be false during Frame 1 composition",
                false,
                frame1ReactivePresentation,
            )
            assertTrue(
                "Frame-1 ActionButton must acquire demand on host.presentations, got ${host!!.presentations.activeDemandCount()}",
                host.presentations.activeDemandCount() > 0,
            )

            runBlocking {
                compose.runOnIdle { focus.requestFocus() }
                compose.awaitIdle()
            }
            control("btn").assertIsEnabled()

            // Disable the binding and explicitly invalidate as the platform TimerListener does.
            compose.runOnIdle { bindingEnabled = false }
            compose.waitForIdle()
            runInEdtAndWait { host.presentations.invalidate() }
            assertFalse(
                "host presentation must disable once bindingEnabled=false",
                host.presentationFor(action.id).enabled,
            )
            compose.waitUntil(5_000) {
                runCatching { control("btn").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled)) }
                    .isSuccess
            }
            control("btn").assertIsNotEnabled()
        } finally {
            actionManager.unregisterAction(actionId)
        }
    }

    @Test
    fun `ShortcutHostBridge refreshes ActionButton when snapshot and provideData state change without ActivityTracker`() {
        val syncId = "jewel.test.bridge.sync"
        val saveId = "jewel.test.bridge.saveParam"
        val syncAction = JewelAction(JewelActionId(syncId), "Sync")
        val saveAction = JewelAction(JewelActionId(saveId), "SaveParam")
        val isOnlineKey = ActionContextKey.create<Boolean>("test.bridge.isOnline")
        val paramKey = ActionContextKey.create<Boolean>("test.bridge.paramFlag")
        val connectivity = MutableStateFlow(true)
        var paramFlag by mutableStateOf(true)
        val focus = FocusRequester()

        val actionManager = ActionManager.getInstance()
        val syncDeclared =
            JewelActionBridgeAction().apply {
                templatePresentation.text = "Sync"
                templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
            }
        val saveDeclared =
            JewelActionBridgeAction().apply {
                templatePresentation.text = "SaveParam"
                templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
            }
        actionManager.registerAction(syncId, syncDeclared)
        actionManager.registerAction(saveId, saveDeclared)
        try {
            compose.setContent {
                val isOnline by connectivity.collectAsState()
                val capturedParam = paramFlag
                SwingBridgeTheme {
                    CompositionLocalProvider(
                        LocalComponent provides wrapper,
                        LocalPopupRenderer provides JBPopupRenderer,
                    ) {
                        ShortcutHostBridge(wrapper) {
                            ComponentDataProviderBridge(wrapper) {
                                Box(
                                    Modifier.provideData { set(isOnlineKey.name, isOnline) }
                                        .provideData { set(paramKey.name, capturedParam) }
                                        .shortcut(syncAction, update = { enabled = context[isOnlineKey] == true }) {}
                                        .shortcut(saveAction, update = { enabled = context[paramKey] == true }) {}
                                        .focusRequester(focus)
                                        .focusable()
                                ) {
                                    ActionButton(syncAction, modifier = Modifier.testTag("sync"))
                                    ActionButton(saveAction, modifier = Modifier.testTag("saveParam"))
                                }
                            }
                        }
                    }
                }
            }

            runBlocking {
                compose.runOnIdle { focus.requestFocus() }
                compose.awaitIdle()
            }
            runInEdtAndWait { UIUtil.dispatchAllInvocationEvents() }
            compose.waitForIdle()

            control("sync").assertIsEnabled()
            control("saveParam").assertIsEnabled()

            // Flip Flow state with NO ActivityTracker increment and NO manual invalidate().
            connectivity.value = false
            compose.waitUntil(5_000) {
                runInEdtAndWait { UIUtil.dispatchAllInvocationEvents() }
                runCatching { control("sync").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled)) }
                    .isSuccess
            }
            control("sync").assertIsNotEnabled()

            // Restore online state.
            connectivity.value = true
            compose.waitUntil(5_000) {
                runInEdtAndWait { UIUtil.dispatchAllInvocationEvents() }
                runCatching { control("sync").assertIsEnabled() }.isSuccess
            }
            control("sync").assertIsEnabled()

            // Flip captured non-snapshot parameter fed into Modifier.provideData (exercises
            // DataProviderElement.update).
            compose.runOnIdle { paramFlag = false }
            compose.waitUntil(5_000) {
                runInEdtAndWait { UIUtil.dispatchAllInvocationEvents() }
                runCatching { control("saveParam").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled)) }
                    .isSuccess
            }
            control("saveParam").assertIsNotEnabled()
            assertFalse(
                "sanity check: reactivePresentation stays false on bridge",
                wrapper.shortcutHostState!!.reactivePresentation,
            )
        } finally {
            actionManager.unregisterAction(syncId)
            actionManager.unregisterAction(saveId)
        }
    }
}
