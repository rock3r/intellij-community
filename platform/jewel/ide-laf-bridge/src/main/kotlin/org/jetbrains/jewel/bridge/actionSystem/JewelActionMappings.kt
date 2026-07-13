// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.actionSystem

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.IdeActions
import java.awt.Component
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.shortcut.ActionDispatchRejection
import org.jetbrains.jewel.foundation.shortcut.ActionDispatchResult
import org.jetbrains.jewel.foundation.shortcut.ActionInvoker
import org.jetbrains.jewel.foundation.shortcut.ActionTrigger
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.jetbrains.jewel.foundation.shortcut.JewelActions

/**
 * Explicit routing of Jewel action IDs onto existing platform actions (the `MappedIdeAction` route).
 * A mapped action keeps its native `AnAction` class, `update()`, presentation, and `DataContext`
 * semantics — the mapping only tells the bridge invoker which platform action to execute. Mapped actions
 * never emit Jewel lifecycle events; observe them through ordinary IJPL facilities.
 *
 * Applications may override a default mapping before any invocation; [map] validates that the target
 * platform action exists.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public object JewelActionMappings {
    private val mappings = ConcurrentHashMap<JewelActionId, String>()

    public fun map(jewelActionId: JewelActionId, ideActionId: String) {
        requireNotNull(ActionManager.getInstance().getAction(ideActionId)) {
            "Unknown platform action ID '$ideActionId' for Jewel action '${jewelActionId.value}'"
        }
        mappings[jewelActionId] = ideActionId
    }

    public fun ideActionIdFor(jewelActionId: JewelActionId): String? = mappings[jewelActionId]

    /** Bridge defaults for the standard edit actions; call once at bridge startup. */
    public fun installStandardMappings() {
        map(JewelActions.Copy.id, IdeActions.ACTION_COPY)
        map(JewelActions.Cut.id, IdeActions.ACTION_CUT)
        map(JewelActions.Paste.id, IdeActions.ACTION_PASTE)
        map(JewelActions.SelectAll.id, IdeActions.ACTION_SELECT_ALL)
    }
}

/**
 * Bridge-side [ActionInvoker]: routes a mapped ID to its platform action, otherwise to the bridge-owned
 * [JewelActionBridgeAction], always via `ActionManager.tryToExecute` with the Jewel host as the context
 * component — listeners, transactions, and dumb-mode handling stay on the platform path. Submission
 * reports dispatch acceptance, not completion.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class JewelBridgeActionInvoker(
    private val hostComponent: Component,
    private val actionManager: ActionManager = ActionManager.getInstance(),
) : ActionInvoker {
    override fun invoke(action: JewelAction, trigger: ActionTrigger): ActionDispatchResult {
        val targetId = JewelActionMappings.ideActionIdFor(action.id) ?: action.id.value
        val target =
            actionManager.getAction(targetId)
                ?: return ActionDispatchResult.Rejected(ActionDispatchRejection.Unregistered)
        actionManager.tryToExecute(target, null, hostComponent, ActionPlaces.UNKNOWN, true)
        return ActionDispatchResult.Dispatched
    }
}
