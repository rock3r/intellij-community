// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.dp
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.shortcut.ActionContext
import org.jetbrains.jewel.foundation.shortcut.ActionPresentation
import org.jetbrains.jewel.foundation.shortcut.ActionTrigger
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionGroup
import org.jetbrains.jewel.foundation.shortcut.JewelActionKind
import org.jetbrains.jewel.foundation.shortcut.JewelMenuEntry
import org.jetbrains.jewel.foundation.shortcut.LocalJewelShortcutHost
import org.jetbrains.jewel.foundation.shortcut.collectPresentationAsState
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.icon.IconKey

/**
 * The presentation's icon as a Jewel [IconKey], or null when the host put a representation this UI does
 * not render (the slot is host-interpreted; see [ActionPresentation.icon]).
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public fun ActionPresentation.iconKeyOrNull(): IconKey? = icon as? IconKey

/**
 * A button bound to a host-registered [JewelAction]: it renders the action's sampled presentation
 * (text, icon, enabled, visibility) and invokes through the host, so it always agrees with the same
 * action's keyboard shortcut about enablement and behavior.
 *
 * Requires a [LocalJewelShortcutHost]; without one the button renders disabled with the action title
 * (the HostUnavailable presentation). With [respectVisibility] (the default) a hidden action emits
 * nothing; a hidden-but-enabled action remains keymap-invocable regardless, matching IJPL visibility
 * semantics.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun ActionButton(action: JewelAction, modifier: Modifier = Modifier, respectVisibility: Boolean = true) {
    val host = LocalJewelShortcutHost.current
    if (host == null) {
        val presentation = ActionPresentation.hostUnavailable(action)
        DefaultButton(onClick = {}, modifier = modifier, enabled = false) {
            ActionButtonContent(presentation)
        }
        return
    }
    val presentation by action.collectPresentationAsState(host.presentations)
    if (respectVisibility && !presentation.visible) return
    DefaultButton(
        onClick = { host.invoker.invoke(action, ActionTrigger.Pointer) },
        modifier = modifier,
        enabled = presentation.enabled,
    ) {
        ActionButtonContent(presentation)
    }
}

@Composable
private fun ActionButtonContent(presentation: ActionPresentation) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        presentation.iconKeyOrNull()?.let { Icon(key = it, contentDescription = null) }
        Text(presentation.text)
    }
}

/**
 * A toggle control bound to a [JewelActionKind.Toggle] action. The checked state is the sampled
 * presentation's `selected` value — the single source of truth a binding provides through its
 * `ActionPresentationOverride` — and activation always routes through the host invoker, so pointer,
 * keyboard, and programmatic toggling stay in agreement.
 *
 * Renders as a selectable icon button when the presentation carries a Jewel icon, and as a labeled
 * checkbox row otherwise; both expose toggle semantics to accessibility (the icon variant declares
 * [Role.Checkbox] and its [ToggleableState] explicitly).
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun ToggleActionButton(action: JewelAction, modifier: Modifier = Modifier, respectVisibility: Boolean = true) {
    require(action.kind == JewelActionKind.Toggle) {
        "ToggleActionButton requires a Toggle action; '${action.id.value}' is ${action.kind}. " +
            "Use ActionButton for Command actions."
    }
    val host = LocalJewelShortcutHost.current
    if (host == null) {
        CheckboxRow(text = action.title, checked = false, onCheckedChange = {}, modifier = modifier, enabled = false)
        return
    }
    val presentation by action.collectPresentationAsState(host.presentations)
    if (respectVisibility && !presentation.visible) return

    val iconKey = presentation.iconKeyOrNull()
    if (iconKey != null) {
        SelectableIconButton(
            selected = presentation.selected,
            onClick = { host.invoker.invoke(action, ActionTrigger.Pointer) },
            modifier =
                modifier.semantics {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(presentation.selected)
                },
            enabled = presentation.enabled,
        ) {
            Icon(key = iconKey, contentDescription = presentation.text, modifier = Modifier.padding(2.dp))
        }
    } else {
        CheckboxRow(
            text = presentation.text,
            checked = presentation.selected,
            onCheckedChange = { host.invoker.invoke(action, ActionTrigger.Pointer) },
            modifier = modifier,
            enabled = presentation.enabled,
        )
    }
}

/**
 * A toolbar over a [JewelActionGroup]: leaf actions render as [ActionButton]s (or [ToggleActionButton]s
 * for toggle actions), separators as vertical [Divider]s, and non-popup subgroups expand inline
 * recursively. Popup subgroups render as an [ActionMenuButton] hosting the group's menu.
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
            is JewelMenuEntry.Action ->
                if (entry.action.kind == JewelActionKind.Toggle) {
                    ToggleActionButton(entry.action)
                } else {
                    ActionButton(entry.action)
                }
            is JewelMenuEntry.Separator ->
                Divider(orientation = Orientation.Vertical, modifier = Modifier.height(20.dp))
            is JewelMenuEntry.Group ->
                if (entry.group.presentation.visible) {
                    if (entry.group.presentation.popup) {
                        ActionMenuButton(entry.group, context = context)
                    } else {
                        ActionToolbarEntries(entry.group, context)
                    }
                }
        }
    }
}
