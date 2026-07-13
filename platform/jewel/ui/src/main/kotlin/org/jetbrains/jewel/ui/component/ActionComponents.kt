// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.shortcut.ActionContext
import org.jetbrains.jewel.foundation.shortcut.ActionTrigger
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionGroup
import org.jetbrains.jewel.foundation.shortcut.JewelMenuEntry
import org.jetbrains.jewel.foundation.shortcut.LocalJewelShortcutHost
import org.jetbrains.jewel.foundation.shortcut.collectPresentationAsState
import org.jetbrains.jewel.ui.Orientation

/**
 * A button bound to a host-registered [JewelAction]: it renders the action's sampled presentation
 * (text, enabled, visibility) and invokes through the host, so it always agrees with the same action's
 * keyboard shortcut about enablement and behavior.
 *
 * Requires a [LocalJewelShortcutHost]; without one the button renders disabled with the action title.
 * With [respectVisibility] (the default) a hidden action emits nothing; a hidden-but-enabled action
 * remains keymap-invocable regardless, matching IJPL visibility semantics.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun ActionButton(action: JewelAction, modifier: Modifier = Modifier, respectVisibility: Boolean = true) {
    val host = LocalJewelShortcutHost.current
    if (host == null) {
        DefaultButton(onClick = {}, modifier = modifier, enabled = false) { Text(action.title) }
        return
    }
    val presentation by action.collectPresentationAsState(host.presentations)
    if (respectVisibility && !presentation.visible) return
    DefaultButton(
        onClick = { host.invoker.invoke(action, ActionTrigger.Pointer) },
        modifier = modifier,
        enabled = presentation.enabled,
    ) {
        Text(action.title)
    }
}

/**
 * A toolbar over a [JewelActionGroup]: leaf actions render as [ActionButton]s, separators as vertical
 * [Divider]s, and non-popup subgroups expand inline recursively. Popup subgroups render as a disabled
 * disclosure button in this slice — menu hosting lands with the menu integration, which must also thread
 * the shortcut host's key handler into its popups.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun ActionToolbar(
    group: JewelActionGroup,
    modifier: Modifier = Modifier,
    context: ActionContext = ActionContext.Empty,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionToolbarEntries(group, context)
    }
}

@Composable
private fun ActionToolbarEntries(group: JewelActionGroup, context: ActionContext) {
    for (entry in group.children(context)) {
        when (entry) {
            is JewelMenuEntry.Action -> ActionButton(entry.action)
            is JewelMenuEntry.Separator ->
                Divider(orientation = Orientation.Vertical, modifier = Modifier.height(20.dp))
            is JewelMenuEntry.Group ->
                if (entry.group.presentation.visible) {
                    if (entry.group.presentation.popup) {
                        OutlinedButton(onClick = {}, enabled = false) {
                            Text("${entry.group.presentation.text} ▾")
                        }
                    } else {
                        ActionToolbarEntries(entry.group, context)
                    }
                }
        }
    }
}
