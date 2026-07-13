// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.shortcut.ActionContext
import org.jetbrains.jewel.foundation.shortcut.ActionPresentation
import org.jetbrains.jewel.foundation.shortcut.ActionTrigger
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionGroup
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.jetbrains.jewel.foundation.shortcut.JewelActionKind
import org.jetbrains.jewel.foundation.shortcut.JewelMenuEntry
import org.jetbrains.jewel.foundation.shortcut.JewelShortcutHostState
import org.jetbrains.jewel.foundation.shortcut.LocalJewelShortcutHost
import org.jetbrains.jewel.foundation.shortcut.MenuDismissPolicy
import org.jetbrains.jewel.foundation.shortcut.collectPresentationAsState

/**
 * A popup menu hosting a [JewelActionGroup]: leaf actions render as menu items with their sampled
 * presentation (toggles as checkable items with checkbox accessibility semantics), separators as menu
 * separators, and popup subgroups as submenus. Item activation routes through the host invoker.
 *
 * The popup threads the shortcut host's key handler into its scene layer, so keyboard dispatch — and the
 * menu's own item shortcuts, absorbed into the host as a menu scope — keeps working while the menu is
 * open; popup layers never inherit window key hooks on their own.
 *
 * Whether performing an item dismisses the menu follows the item presentation's [MenuDismissPolicy]
 * (defaulting per action kind: commands dismiss, toggles honor [keepPopupsForToggles]).
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun ActionMenu(
    group: JewelActionGroup,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    context: ActionContext = ActionContext.Empty,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    keepPopupsForToggles: Boolean = false,
) {
    val host = LocalJewelShortcutHost.current ?: return
    val entries = remember(group, context) { group.children(context) }
    val presentations = collectLeafPresentations(host, entries)

    PopupMenu(
        onDismissRequest = { _: InputMode ->
            onDismissRequest()
            true
        },
        horizontalAlignment = horizontalAlignment,
        modifier = modifier,
    ) {
        actionEntries(host, entries, presentations, context, keepPopupsForToggles)
    }
}

@Composable
private fun collectLeafPresentations(
    host: JewelShortcutHostState,
    entries: List<JewelMenuEntry>,
): Map<JewelActionId, ActionPresentation> {
    val result = mutableMapOf<JewelActionId, ActionPresentation>()
    CollectLeafPresentationsInto(result, host, entries)
    return result
}

@Composable
private fun CollectLeafPresentationsInto(
    sink: MutableMap<JewelActionId, ActionPresentation>,
    host: JewelShortcutHostState,
    entries: List<JewelMenuEntry>,
) {
    for (entry in entries) {
        when (entry) {
            is JewelMenuEntry.Action -> {
                val presentation by entry.action.collectPresentationAsState(host.presentations)
                sink[entry.action.id] = presentation
            }
            is JewelMenuEntry.Group ->
                CollectLeafPresentationsInto(sink, host, entry.group.children(ActionContext.Empty))
            is JewelMenuEntry.Separator -> Unit
        }
    }
}

private fun MenuScope.actionEntries(
    host: JewelShortcutHostState,
    entries: List<JewelMenuEntry>,
    presentations: Map<JewelActionId, ActionPresentation>,
    context: ActionContext,
    keepPopupsForToggles: Boolean,
) {
    for (entry in entries) {
        when (entry) {
            is JewelMenuEntry.Action -> {
                val action = entry.action
                val presentation = presentations[action.id] ?: ActionPresentation.hostUnavailable(action)
                if (!presentation.visible) continue
                actionItem(
                    selected = presentation.selected,
                    role =
                        if (action.kind == JewelActionKind.Toggle) MenuItemAccessibilityRole.Checkbox
                        else MenuItemAccessibilityRole.Item,
                    iconKey = presentation.iconKeyOrNull(),
                    keybinding = keybindingHintFor(host, action),
                    keepMenuOpenOnClick = keepsMenuOpen(action, presentation, keepPopupsForToggles),
                    onClick = { host.invoker.invoke(action, ActionTrigger.Pointer) },
                    enabled = presentation.enabled,
                ) {
                    Text(presentation.text)
                }
            }
            is JewelMenuEntry.Separator -> separator()
            is JewelMenuEntry.Group ->
                if (entry.group.presentation.visible) {
                    submenu(
                        submenu = {
                            actionEntries(
                                host,
                                entry.group.children(context),
                                presentations,
                                context,
                                keepPopupsForToggles,
                            )
                        }
                    ) {
                        Text(entry.group.presentation.text)
                    }
                }
        }
    }
}

private fun keybindingHintFor(host: JewelShortcutHostState, action: JewelAction): Set<String>? =
    host.shortcutsFor(action.id).firstOrNull()?.displayText()?.split("+")?.toSet()

private fun keepsMenuOpen(
    action: JewelAction,
    presentation: ActionPresentation,
    keepPopupsForToggles: Boolean,
): Boolean {
    val policy =
        presentation.menuDismissPolicy
            ?: if (action.kind == JewelActionKind.Toggle) MenuDismissPolicy.KeepIfPreferred
            else MenuDismissPolicy.Dismiss
    return when (policy) {
        MenuDismissPolicy.Dismiss -> false
        // Explicit keep requests (a keyboard modifier held on activation) are not modeled yet; without
        // one, IfRequested closes like Dismiss.
        MenuDismissPolicy.KeepIfRequested -> false
        MenuDismissPolicy.KeepIfPreferred -> keepPopupsForToggles
        MenuDismissPolicy.KeepAlways -> true
    }
}

/**
 * A button that opens an [ActionMenu] for [group]. The anchor renders the group's presentation text with
 * a disclosure chevron; the menu inherits the shortcut host threading and dismiss policies of
 * [ActionMenu].
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun ActionMenuButton(
    group: JewelActionGroup,
    modifier: Modifier = Modifier,
    context: ActionContext = ActionContext.Empty,
    keepPopupsForToggles: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }) { Text("${group.presentation.text} ▾") }
        if (expanded) {
            ActionMenu(
                group = group,
                onDismissRequest = { expanded = false },
                context = context,
                keepPopupsForToggles = keepPopupsForToggles,
            )
        }
    }
}

/**
 * A split button: the primary segment invokes [primary] through the host (an [ActionButton]), and the
 * chevron segment opens an [ActionMenu] for [menuGroup].
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun SplitActionButton(
    primary: JewelAction,
    menuGroup: JewelActionGroup,
    modifier: Modifier = Modifier,
    context: ActionContext = ActionContext.Empty,
    keepPopupsForToggles: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(modifier) {
        ActionButton(primary)
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text("▾") }
            if (expanded) {
                ActionMenu(
                    group = menuGroup,
                    onDismissRequest = { expanded = false },
                    context = context,
                    keepPopupsForToggles = keepPopupsForToggles,
                )
            }
        }
    }
}
