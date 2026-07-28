// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.unit.dp
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
import org.jetbrains.jewel.foundation.shortcut.selected
import org.jetbrains.jewel.foundation.shortcut.showsTextInToolbar
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.styling.IconButtonStyle
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.iconButtonStyle

/**
 * A popup menu hosting a [JewelActionGroup]: leaf actions render as menu items with their sampled presentation (toggles
 * as checkable items with checkbox accessibility semantics), separators as menu separators, and popup subgroups as
 * submenus. Item activation routes through the host invoker.
 *
 * The popup threads the shortcut host's key handler into its scene layer, so keyboard dispatch — and the menu's own
 * item shortcuts, absorbed into the host as a menu scope — keeps working while the menu is open; popup layers never
 * inherit window key hooks on their own.
 *
 * Whether performing an item dismisses the menu follows the item presentation's [MenuDismissPolicy] (defaulting per
 * action kind: commands dismiss, toggles honor [keepPopupsForToggles]).
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun ActionMenu(
    group: JewelActionGroup,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    keepPopupsForToggles: Boolean = false,
) {
    val host = LocalJewelShortcutHost.current ?: return
    val context = host.currentActionContext()
    val entries = group.children(context)
    val presentations = collectLeafPresentations(host, entries, context)

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
    context: ActionContext,
): Map<JewelActionId, ActionPresentation> {
    val leafActions = remember(entries) { flattenLeafActions(entries, context) }
    val result = mutableMapOf<JewelActionId, ActionPresentation>()
    for (action in leafActions) {
        val presentation by action.collectPresentationAsState(host)
        result[action.id] = presentation
    }
    return result
}

private fun flattenLeafActions(entries: List<JewelMenuEntry>, context: ActionContext): List<JewelAction> = buildList {
    for (entry in entries) {
        when (entry) {
            is JewelMenuEntry.Action -> add(entry.action)
            is JewelMenuEntry.Group -> addAll(flattenLeafActions(entry.group.children(context), context))
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
                    icon = presentation.resolveIcon(action),
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
 * A split button bound to actions: the primary segment invokes [primary] through the host, and the chevron segment
 * opens a menu built from [menuGroup].
 *
 * It is styled as an action button, not as a bordered one, because Swing's `SplitButtonAction` builds a `SplitButton`
 * that **extends `ActionButton`** — it paints through `ActionButtonLook`, so it is borderless until hovered, and its
 * chevron is `AllIcons.General.ButtonDropTriangle` with a separator that appears only on hover or press. (Jewel's
 * [DefaultSplitButton]/[OutlinedSplitButton] correspond to Swing's bordered `JBOptionButton`, which is a dialog control
 * rather than an action one.)
 *
 * Rendered as a single control, matching Swing: one shared hover background lights the whole button when either zone is
 * hovered, only the *pressed* zone takes the darker pressed fill, and the separator appears only on hover or press. As
 * in `SplitButtonAction`, a disabled primary makes the whole button open the popup rather than doing nothing.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun SplitActionButton(
    primary: JewelAction,
    menuGroup: JewelActionGroup,
    modifier: Modifier = Modifier,
    keepPopupsForToggles: Boolean = false,
    style: IconButtonStyle = JewelTheme.iconButtonStyle,
) {
    val host = LocalJewelShortcutHost.current
    val presentation =
        if (host == null) ActionPresentation.hostUnavailable(primary)
        else primary.collectPresentationAsState(host).value
    if (!presentation.visible) return

    var expanded by remember { mutableStateOf(false) }
    val primaryEnabled = host != null && presentation.enabled

    val rootSource = remember { MutableInteractionSource() }
    val actionSource = remember { MutableInteractionSource() }
    val arrowSource = remember { MutableInteractionSource() }
    // Hover is tracked on the whole control, not per zone: Swing hit-tests one component, so moving across the 1px
    // separator (which belongs to neither clickable zone) must not drop the shared background.
    val hovered by rootSource.collectIsHoveredAsState()
    val actionPressed by actionSource.collectIsPressedAsState()
    val arrowPressed by arrowSource.collectIsPressedAsState()

    val separatorVisible = hovered || actionPressed || arrowPressed

    val colors = style.colors
    val cornerSize = style.metrics.cornerSize
    val minSize = style.metrics.minSize

    Box(modifier) {
        // One shared hover background spans the whole control, exactly like Swing's SplitButton: hovering either zone
        // lights the entire button; only the *pressed* zone takes the darker pressed fill on top.
        Row(
            Modifier.hoverable(rootSource)
                .background(
                    if (hovered) colors.backgroundHovered else Color.Transparent,
                    RoundedCornerShape(cornerSize),
                )
                .height(minSize.height),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.fillMaxHeight()
                    .background(
                        if (actionPressed) colors.backgroundPressed else Color.Transparent,
                        RoundedCornerShape(
                            topStart = cornerSize,
                            topEnd = ZeroCornerSize,
                            bottomEnd = ZeroCornerSize,
                            bottomStart = cornerSize,
                        ),
                    )
                    .clickable(interactionSource = actionSource, indication = null, enabled = host != null) {
                        // As in SplitButtonAction, a disabled primary makes the whole button open the popup.
                        if (primaryEnabled) host?.invoker?.invoke(primary, ActionTrigger.Pointer)
                        else expanded = !expanded
                    }
                    .defaultMinSize(minWidth = minSize.width)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.alpha(if (primaryEnabled) 1f else 0.5f)) {
                    ActionButtonContent(presentation, primary, presentation.showsTextInToolbar)
                }
            }

            // The separator shows only while the control is hovered or pressed, as in SplitButtonAction.paintComponent.
            Box(
                Modifier.width(1.dp)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .background(if (separatorVisible) JewelTheme.globalColors.borders.normal else Color.Transparent)
            )

            Box(
                Modifier.fillMaxHeight()
                    .background(
                        if (arrowPressed) colors.backgroundPressed else Color.Transparent,
                        RoundedCornerShape(
                            topStart = ZeroCornerSize,
                            topEnd = cornerSize,
                            bottomEnd = cornerSize,
                            bottomStart = ZeroCornerSize,
                        ),
                    )
                    .clickable(interactionSource = arrowSource, indication = null, enabled = host != null) {
                        expanded = !expanded
                    }
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AllIconsKeys.General.ButtonDropTriangle, contentDescription = null)
            }
        }
        if (expanded && host != null) {
            ActionMenu(
                group = menuGroup,
                onDismissRequest = { expanded = false },
                keepPopupsForToggles = keepPopupsForToggles,
            )
        }
    }
}
