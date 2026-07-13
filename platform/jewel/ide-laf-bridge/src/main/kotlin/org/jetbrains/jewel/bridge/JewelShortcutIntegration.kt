// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.TimerListener
import com.intellij.openapi.application.ModalityState
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent as AwtKeyEvent
import javax.swing.SwingUtilities
import org.jetbrains.jewel.bridge.actionSystem.JewelActionMappings
import org.jetbrains.jewel.bridge.actionSystem.JewelBridgeActionInvoker
import org.jetbrains.jewel.foundation.shortcut.InMemoryJewelKeymap
import org.jetbrains.jewel.foundation.shortcut.JewelShortcutHostState
import org.jetbrains.jewel.foundation.shortcut.ProvideJewelShortcutHost
import org.jetbrains.jewel.foundation.shortcut.toComposeKeyEvent

/**
 * Wires the Jewel shortcut claim lane into a bridge-hosted Compose panel.
 *
 * Two cooperating halves, matching the PRD's bridge contract:
 * - **Veto**: [JewelComposePanelWrapper] implements `KeyboardAwareFocusOwnerProvider`; when a focused
 *   `Modifier.claimShortcut`/`claimKeyEvent` node owns a key-down, `IdeKeyEventDispatcher` skips IDE keymap processing.
 *   The wrapper — not the focus owner — answers, because the actual AWT focus owner inside a `ComposePanel` is an
 *   internal skiko component the embedder cannot control.
 * - **Delivery**: a [KeyEventDispatcher] scoped to this wrapper's focused descendants consumes the claimed key-down,
 *   invokes the claim handler, and swallows the trailing KEY_TYPED so claimed printable keys do not leak characters
 *   into focused text fields. It runs at the KeyboardFocusManager stage — after the IDE keymap has already declined the
 *   event via the veto — so no Compose Multiplatform hook is required.
 *
 * Commands (`Modifier.shortcut`) are intentionally NOT dispatched here: in the IJPL host they remain platform actions
 * resolved through the IDE keymap (the bridge action registry is a follow-up slice), so the bridge host state uses an
 * empty Jewel keymap.
 */
@Composable
internal fun ShortcutHostBridge(wrapper: JewelComposePanelWrapper, content: @Composable () -> Unit) {
    val bridgeKeymap = remember { InMemoryJewelKeymap("jewel-bridge-claims-only") }
    val state = remember { JewelShortcutHostState(keymapProvider = { bridgeKeymap }) }

    DisposableEffect(wrapper, state) {
        // Idempotent and non-clobbering; running it per panel keeps the bridge free of startup hooks
        // while guaranteeing the standard edit actions route to the platform before any invocation.
        JewelActionMappings.installStandardMappings()
        // Route programmatic invocations (ActionButton and friends) through the platform action system,
        // so ActionManager update, enablement, listeners, and dumb-mode handling stay authoritative.
        state.invoker = JewelBridgeActionInvoker(wrapper)

        wrapper.shortcutHostState = state
        wrapper.shortcutClaimEvaluator = { awtEvent ->
            awtEvent.id == AwtKeyEvent.KEY_PRESSED && state.claimsKeyDown(awtEvent.toComposeKeyEvent())
        }

        // Presentation sampling rides the platform's action-update cadence: the same timer that refreshes
        // toolbars re-samples Jewel presentations, but only when user activity actually advanced and only
        // for actions some composed control is observing (the scheduler is demand-driven).
        val actionManager = ActionManager.getInstance()
        var lastActivityCount = -1
        val presentationTimerListener =
            object : TimerListener {
                override fun getModalityState(): ModalityState = ModalityState.any()

                override fun run() {
                    if (state.presentations.activeDemandCount() == 0) return
                    val count = ActivityTracker.getInstance().count
                    if (count != lastActivityCount) {
                        lastActivityCount = count
                        state.presentations.invalidate()
                    }
                }
            }
        actionManager.addTimerListener(presentationTimerListener)

        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val claimDeliveryDispatcher = KeyEventDispatcher { event ->
            val focusOwner = focusManager.focusOwner
            if (focusOwner == null || !SwingUtilities.isDescendingFrom(focusOwner, wrapper)) {
                return@KeyEventDispatcher false
            }
            val consumed =
                when (event.id) {
                    AwtKeyEvent.KEY_PRESSED,
                    AwtKeyEvent.KEY_TYPED -> state.onPreviewKeyEvent(event.toComposeKeyEvent())
                    else -> false
                }
            if (consumed) event.consume()
            consumed
        }
        focusManager.addKeyEventDispatcher(claimDeliveryDispatcher)

        onDispose {
            actionManager.removeTimerListener(presentationTimerListener)
            focusManager.removeKeyEventDispatcher(claimDeliveryDispatcher)
            wrapper.shortcutClaimEvaluator = null
            wrapper.shortcutHostState = null
            state.reset()
        }
    }

    ProvideJewelShortcutHost(state) { Box(state.resolverRootModifier) { content() } }
}
