// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.actionSystem

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.project.DumbAware
import java.awt.Component
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.bridge.JewelComposePanelWrapper
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.InternalJewelApi
import org.jetbrains.jewel.foundation.shortcut.ActionResolution
import org.jetbrains.jewel.foundation.shortcut.ActionTrigger
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.jetbrains.jewel.foundation.shortcut.JewelShortcutHostState
import org.jetbrains.jewel.foundation.shortcut.MenuDismissPolicy

/**
 * The generic bridge action for Jewel commands, intended for normal plugin.xml `<action>` registration (its IJPL action
 * ID must equal the corresponding [JewelActionId] value) or runtime registration through `JewelBridgeActionRegistry`.
 *
 * It holds no Compose state: `update()` and `actionPerformed()` obtain the action's own registered ID from
 * [ActionManager], locate the focused Jewel host from the event's context component, and resolve the nearest focused
 * enabled `Modifier.shortcut` binding at that moment. Declarative and runtime actions are therefore identical from the
 * keymap's perspective, and a stale binding can never execute: perform always re-resolves.
 *
 * Runs on BGT: resolution only reads the host's immutable focused-binding snapshot; no Swing hierarchy is touched
 * beyond walking the context component's ancestors.
 *
 * Enabled in modal contexts by design: Jewel content hosted inside modal dialogs participates in shortcut dispatch like
 * any other surface, and execution is already gated on a focused enabled binding, so the modal flag adds no
 * reachability an unfocused surface would not have.
 *
 * The class is deliberately not [DumbAware]: a Jewel binding's handler is arbitrary application code. Declare
 * [DumbAwareJewelActionBridgeAction] in plugin.xml for actions whose every focused handler is safe during indexing;
 * runtime registrations through `JewelBridgeActionRegistry` always use this class.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public open class JewelActionBridgeAction : AnAction() {
    init {
        isEnabledInModalContext = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val id = ActionManager.getInstance().getId(this)
        val host = if (id != null) jewelHostStateFor(event) else null
        val presentation = if (id != null && host != null) host.presentationFor(JewelActionId(id)) else null
        if (presentation == null || presentation.resolution != ActionResolution.Resolved) {
            // Template visibility and text are preserved: a declared action stays discoverable in menus
            // while correctly refusing execution without a focused binding (or without a Jewel host).
            event.presentation.isEnabled = false
            return
        }
        event.presentation.isEnabled = presentation.enabled
        presentation.description?.let { event.presentation.description = it }
        // The focused binding's override rides the platform presentation where IJPL has an equivalent:
        // toggle state for checkable menu items, and popup retention (the 4-state KeepPopupOnPerform).
        Toggleable.setSelected(event.presentation, presentation.selected)
        presentation.menuDismissPolicy?.let { event.presentation.keepPopupOnPerform = it.toPlatform() }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val id = ActionManager.getInstance().getId(this) ?: return
        val host = jewelHostStateFor(event) ?: return
        val handler = host.resolveFocusedHandler(JewelActionId(id)) ?: return
        // Exactly one Jewel event per completed bridge-owned invocation, whatever invoked the platform
        // action (IDE keymap, menus, tryToExecute). Mapped platform actions never come through here and
        // deliberately never emit.
        host.runResolvedInvocation(JewelActionId(id), triggerFor(event), handler)
    }

    private fun resolveFocusedHandler(event: AnActionEvent): (() -> Unit)? {
        val id = ActionManager.getInstance().getId(this) ?: return null
        val host = jewelHostStateFor(event) ?: return null
        return host.resolveFocusedHandler(JewelActionId(id))
    }

    private fun triggerFor(event: AnActionEvent): ActionTrigger =
        when (event.inputEvent) {
            // The invoking sequence belongs to the IDE keymap, not a Jewel keymap: report a keyboard
            // trigger without claiming a Jewel sequence.
            is KeyEvent -> ActionTrigger.Keyboard(null)
            is MouseEvent -> ActionTrigger.Pointer
            else -> ActionTrigger.Programmatic
        }

    private fun MenuDismissPolicy.toPlatform(): KeepPopupOnPerform =
        when (this) {
            MenuDismissPolicy.Dismiss -> KeepPopupOnPerform.Never
            MenuDismissPolicy.KeepIfRequested -> KeepPopupOnPerform.IfRequested
            MenuDismissPolicy.KeepIfPreferred -> KeepPopupOnPerform.IfPreferred
            MenuDismissPolicy.KeepAlways -> KeepPopupOnPerform.Always
        }

    private fun jewelHostStateFor(event: AnActionEvent): JewelShortcutHostState? {
        // The wrapper sinks its host into the data context (JewelComposePanelWrapper.uiDataSnapshot),
        // so the normal path reads the snapshotted value — safe on BGT with no Swing hierarchy access.
        event.getData(JEWEL_SHORTCUT_HOST_STATE)?.let {
            return it
        }
        // Fallback for contexts built without the wrapper's snapshot (e.g. a bare component context).
        val component = event.getData(PlatformDataKeys.CONTEXT_COMPONENT) ?: return null
        return findJewelShortcutHost(component)
    }
}

/**
 * The focused surface's shortcut host, snapshotted into the data context by
 * [org.jetbrains.jewel.bridge.JewelComposePanelWrapper] whenever it is in the context component chain.
 */
@ApiStatus.Internal
@InternalJewelApi
public val JEWEL_SHORTCUT_HOST_STATE: DataKey<JewelShortcutHostState> = DataKey.create("JewelShortcutHostState")

/**
 * The [DumbAware] declaration variant of [JewelActionBridgeAction], for plugin.xml `<action>` entries whose focused
 * handlers are all safe to run during indexing. Runtime registrations never use this class: the registry cannot know
 * what a future focused handler will do, so it always registers the non-dumb-aware base class.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public open class DumbAwareJewelActionBridgeAction : JewelActionBridgeAction(), DumbAware

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
