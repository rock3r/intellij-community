// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.actionSystem

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import java.awt.Component
import javax.swing.SwingUtilities
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.bridge.JewelComposePanelWrapper
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.jetbrains.jewel.foundation.shortcut.JewelShortcutHostState

/**
 * The generic bridge action for Jewel commands, intended for normal plugin.xml `<action>` registration
 * (its IJPL action ID must equal the corresponding [JewelActionId] value) or runtime registration through
 * `JewelBridgeActionRegistry`.
 *
 * It holds no Compose state: `update()` and `actionPerformed()` obtain the action's own registered ID from
 * [ActionManager], locate the focused Jewel host from the event's context component, and resolve the
 * nearest focused enabled `Modifier.shortcut` binding at that moment. Declarative and runtime actions are
 * therefore identical from the keymap's perspective, and a stale binding can never execute: perform always
 * re-resolves.
 *
 * Runs on BGT: resolution only reads the host's immutable focused-binding snapshot; no Swing hierarchy is
 * touched beyond walking the context component's ancestors.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public open class JewelActionBridgeAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val handler = resolveFocusedHandler(event)
        event.presentation.isEnabled = handler != null
        // Template visibility is preserved: a declared action stays discoverable in menus while
        // correctly refusing execution without a focused binding.
    }

    override fun actionPerformed(event: AnActionEvent) {
        resolveFocusedHandler(event)?.invoke()
    }

    private fun resolveFocusedHandler(event: AnActionEvent): (() -> Unit)? {
        val id = ActionManager.getInstance().getId(this) ?: return null
        val host = jewelHostStateFor(event) ?: return null
        return host.resolveFocusedHandler(JewelActionId(id))
    }

    private fun jewelHostStateFor(event: AnActionEvent): JewelShortcutHostState? {
        val component = event.getData(PlatformDataKeys.CONTEXT_COMPONENT) ?: return null
        val wrapper =
            SwingUtilities.getAncestorOfClass(JewelComposePanelWrapper::class.java, component)
                as? JewelComposePanelWrapper ?: (component as? JewelComposePanelWrapper)
        return wrapper?.shortcutHostState
    }
}

/** Focused-host lookup shared with the invoker; exposed for tests. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public fun findJewelShortcutHost(component: Component?): JewelShortcutHostState? {
    var current: Component? = component
    while (current != null) {
        if (current is JewelComposePanelWrapper) return current.shortcutHostState
        current = current.parent
    }
    return null
}
