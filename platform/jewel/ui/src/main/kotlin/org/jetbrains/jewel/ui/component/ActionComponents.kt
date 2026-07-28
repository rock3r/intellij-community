// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.intellij.platform.icons.Icon as IconDescriptor
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.shortcut.ActionContext
import org.jetbrains.jewel.foundation.shortcut.ActionPresentation
import org.jetbrains.jewel.foundation.shortcut.ActionTrigger
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionGroup
import org.jetbrains.jewel.foundation.shortcut.JewelActionKind
import org.jetbrains.jewel.foundation.shortcut.JewelMenuEntry
import org.jetbrains.jewel.foundation.shortcut.JewelShortcutHostState
import org.jetbrains.jewel.foundation.shortcut.LocalJewelShortcutHost
import org.jetbrains.jewel.foundation.shortcut.collectPresentationAsState
import org.jetbrains.jewel.foundation.shortcut.selected
import org.jetbrains.jewel.foundation.shortcut.showsTextInToolbar
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.styling.IconButtonColors
import org.jetbrains.jewel.ui.component.styling.IconButtonStyle
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.iconButtonStyle

/**
 * What Swing renders when an action has no icon at all: `ActionButton.getFallbackIcon` substitutes
 * `AllIcons.Toolbar.Unknown` rather than throwing or drawing nothing, so an unconfigured action is visibly wrong
 * instead of invisibly missing. Text-bearing buttons deliberately do *not* use it — `ActionButtonWithText` falls back
 * to a zero-sized icon, because the label already identifies the action.
 */
private val UnknownActionIcon: IconKey = AllIconsKeys.Toolbar.Unknown

/**
 * Resolves the icon a control should draw, applying IJPL's two-tier fallback: the sampled per-place icon first, then
 * the action's template icon. A per-place presentation may deliberately clear the icon (IJPL's `ToggleAction.update`
 * does exactly this in menus, to force a check mark), and the template remains the standing default underneath.
 */
internal fun ActionPresentation.resolveIcon(action: JewelAction): IconDescriptor? = icon ?: action.template.icon

/** The first bound sequence, rendered the way IJPL appends a shortcut hint to an action button's tooltip. */
@Composable
private fun shortcutHintFor(host: JewelShortcutHostState, action: JewelAction): String? =
    remember(host, action) { host.shortcutsFor(action.id).firstOrNull()?.displayText() }

/** Draws the icon slot: the resolved icon, or the unknown-action placeholder when a control requires one. */
@Composable
internal fun ActionIcon(icon: IconDescriptor?, contentDescription: String?, useUnknownFallback: Boolean) {
    when {
        icon != null -> Icon(icon, contentDescription)
        useUnknownFallback -> Icon(UnknownActionIcon, contentDescription)
    }
}

/**
 * The content of an action-bound button: an icon alone, or an icon plus label when the action asks for text. Mirrors
 * `ActionButtonWithText`, including its rule that a missing icon collapses to nothing rather than reserving a gutter.
 */
@Composable
internal fun ActionButtonContent(presentation: ActionPresentation, action: JewelAction, showsText: Boolean) {
    val icon = presentation.resolveIcon(action)
    if (!showsText) {
        ActionIcon(icon, presentation.text, useUnknownFallback = true)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        // No placeholder and no gap when there is no icon: iconTextSpace() collapses to zero for an empty icon.
        ActionIcon(icon, contentDescription = null, useUnknownFallback = false)
        Text(presentation.text)
    }
}

/**
 * A button bound to a host-registered [JewelAction]: it renders the action's sampled presentation and invokes through
 * the host, so it always agrees with the same action's keyboard shortcut about enablement and behavior.
 *
 * This is the action-bound overload of [ActionButton]; it renders through the very same component, so an action button
 * is visually indistinguishable from a hand-wired one — the borderless-until-hovered toolbar button that
 * [IconButtonStyle] describes, matching Swing's `ActionButton` and its `ActionButtonLook`.
 *
 * By default the action renders as an icon, with its name and shortcut in a tooltip. An action whose presentation sets
 * [ActionPropertyNames.ShowTextInToolbar] renders its label instead, matching Swing's `ActionButtonWithText`, and then
 * carries only its description in the tooltip because the label is already visible.
 *
 * Requires a [LocalJewelShortcutHost]; without one the button renders disabled (the HostUnavailable presentation). With
 * [respectVisibility] (the default) a hidden action emits nothing, matching the platform's removal of invisible actions
 * from a toolbar rather than merely hiding them; a hidden-but-enabled action remains keymap-invocable regardless.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun ActionButton(
    action: JewelAction,
    modifier: Modifier = Modifier,
    respectVisibility: Boolean = true,
    style: IconButtonStyle = JewelTheme.iconButtonStyle,
) {
    val host = LocalJewelShortcutHost.current
    if (host == null) {
        val presentation = ActionPresentation.hostUnavailable(action)
        ActionButton(onClick = {}, modifier = modifier, enabled = false, style = style) {
            ActionButtonContent(presentation, action, presentation.showsTextInToolbar)
        }
        return
    }
    val presentation by action.collectPresentationAsState(host)
    if (respectVisibility && !presentation.visible) return

    val showsText = presentation.showsTextInToolbar
    val shortcut = shortcutHintFor(host, action)
    val tooltipText =
        if (showsText) presentation.description else listOfNotNull(presentation.text, shortcut).joinToString("  ")

    if (tooltipText == null) {
        ActionButton(
            onClick = { host.invoker.invoke(action, ActionTrigger.Pointer) },
            modifier = modifier,
            enabled = presentation.enabled,
            style = style,
        ) {
            ActionButtonContent(presentation, action, showsText)
        }
    } else {
        ActionButton(
            onClick = { host.invoker.invoke(action, ActionTrigger.Pointer) },
            tooltip = { Text(tooltipText) },
            modifier = modifier,
            enabled = presentation.enabled,
            style = style,
        ) {
            ActionButtonContent(presentation, action, showsText)
        }
    }
}

/**
 * Restyles a toggleable icon button to Swing's toolbar semantics: `ActionButton.getPopState()` calls
 * `getPopState(isSelected())`, feeding the toggle state in as the *pushed* flag, so a toggled-on toolbar button paints
 * `ActionButton.pressedBackground` — it has no selected look of its own.
 *
 * [ToggleableIconButton]'s selected colors mean something else: they are the tool-window stripe's, where an active
 * selection is the accent blue. Folding the selected colors onto the pressed ones is what keeps an action toolbar
 * looking like the platform's, and doing it here means the shared [IconButtonStyle] is left alone for every other
 * toggle in the UI.
 */
@Composable
private fun IconButtonStyle.asToolbarToggleStyle(): IconButtonStyle =
    remember(this) {
        val c = colors
        IconButtonStyle(
            colors =
                IconButtonColors(
                    // ActionButtonLook paints the background and then the icon untouched; there is no
                    // selected-state recoloring to mirror, so no activated foreground either.
                    foregroundSelectedActivated = Color.Unspecified,
                    background = c.background,
                    backgroundDisabled = c.backgroundDisabled,
                    backgroundSelected = c.backgroundPressed,
                    backgroundSelectedActivated = c.backgroundPressed,
                    backgroundFocused = c.backgroundFocused,
                    backgroundPressed = c.backgroundPressed,
                    backgroundHovered = c.backgroundHovered,
                    border = c.border,
                    borderDisabled = c.borderDisabled,
                    borderSelected = c.borderPressed,
                    borderSelectedActivated = c.borderPressed,
                    borderFocused = c.borderFocused,
                    borderPressed = c.borderPressed,
                    borderHovered = c.borderHovered,
                ),
            metrics = metrics,
        )
    }

/**
 * A toggle control bound to a [JewelActionKind.Toggle] action. The checked state is the sampled presentation's
 * `selected` value — the single source of truth a binding provides — and activation routes through the host invoker, so
 * pointer, keyboard, and programmatic toggling stay in agreement.
 *
 * It renders through [ToggleableIconButton] under [asToolbarToggleStyle], so a selected toggle shows the same
 * pressed-looking background Swing gives it rather than the stripe accent.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun ToggleActionButton(
    action: JewelAction,
    modifier: Modifier = Modifier,
    respectVisibility: Boolean = true,
    style: IconButtonStyle = JewelTheme.iconButtonStyle,
) {
    require(action.kind == JewelActionKind.Toggle) {
        "ToggleActionButton requires a Toggle action; '${action.id.value}' is ${action.kind}. " +
            "Use ActionButton for Command actions."
    }
    val host = LocalJewelShortcutHost.current
    val presentation =
        if (host == null) ActionPresentation.hostUnavailable(action) else action.collectPresentationAsState(host).value
    if (respectVisibility && !presentation.visible) return

    val showsText = presentation.showsTextInToolbar
    ToggleableIconButton(
        value = presentation.selected,
        onValueChange = { host?.invoker?.invoke(action, ActionTrigger.Pointer) },
        modifier =
            modifier.semantics {
                role = Role.Checkbox
                toggleableState = ToggleableState(presentation.selected)
            },
        enabled = host != null && presentation.enabled,
        style = style.asToolbarToggleStyle(),
    ) {
        ActionButtonContent(presentation, action, showsText)
    }
}

/**
 * A toolbar over a [JewelActionGroup]: leaf actions render as [ActionButton]s (or [ToggleActionButton]s for toggles),
 * separators as vertical [Divider]s, and non-popup subgroups expand inline recursively.
 *
 * A popup subgroup renders the way the platform renders one — as an ordinary action button carrying a dropdown badge,
 * not as a labelled menu button — because `ActionToolbarImpl` builds a plain `ActionButton` for a popup group and only
 * `shallPaintDownArrow()` distinguishes it.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun ActionToolbar(
    group: JewelActionGroup,
    modifier: Modifier = Modifier,
    style: IconButtonStyle = JewelTheme.iconButtonStyle,
) {
    // The group expands against the focused surface's context, exactly as the leaf controls resolve their presentation
    // against it; a static group ignores the context, so this is a no-op for the common case.
    val context = LocalJewelShortcutHost.current?.currentActionContext() ?: ActionContext.Empty
    val items = flattenToolbarEntries(group, context)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (item in items) item(style)
    }
}

/** Pre-expands inline subgroups into a flat list of item composables the toolbar row emits in order. */
private fun flattenToolbarEntries(
    group: JewelActionGroup,
    context: ActionContext,
): List<@Composable (IconButtonStyle) -> Unit> = buildList {
    for (entry in group.children(context)) {
        when (entry) {
            is JewelMenuEntry.Action ->
                if (entry.action.kind == JewelActionKind.Toggle) {
                    add { style -> ToggleActionButton(entry.action, style = style) }
                } else {
                    add { style -> ActionButton(entry.action, style = style) }
                }
            is JewelMenuEntry.Separator ->
                add { Divider(orientation = Orientation.Vertical, modifier = Modifier.height(16.dp)) }
            is JewelMenuEntry.Group ->
                if (entry.group.presentation.visible) {
                    if (entry.group.presentation.popup) {
                        add { style -> ActionGroupButton(entry.group, style = style) }
                    } else {
                        addAll(flattenToolbarEntries(entry.group, context))
                    }
                }
        }
    }
}

/**
 * A popup [JewelActionGroup] sitting directly in a toolbar: an action button whose icon carries a dropdown badge in its
 * bottom-right corner, exactly where `ActionButtonLook.paintDownArrow` places `AllIcons.General.Dropdown`. When the
 * group shows text instead, the affordance moves beside the label as `AllIcons.General.LinkDropTriangle`, matching
 * `ActionButtonWithText.getDownArrowIcon`.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun ActionGroupButton(
    group: JewelActionGroup,
    modifier: Modifier = Modifier,
    style: IconButtonStyle = JewelTheme.iconButtonStyle,
    keepPopupsForToggles: Boolean = false,
) {
    val presentation = group.presentation
    if (!presentation.visible) return
    var expanded by remember { mutableStateOf(false) }
    // No icon means there is nothing for a dropdown badge to sit on, so the group falls back to the
    // text-plus-triangle form rather than rendering the unknown-action placeholder.
    val showsText = presentation.icon == null

    Box(modifier) {
        ActionButton(onClick = { expanded = !expanded }, tooltip = { Text(presentation.text) }, style = style) {
            if (showsText) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(presentation.text)
                    Icon(AllIconsKeys.General.LinkDropTriangle, contentDescription = null)
                }
            } else {
                Box(contentAlignment = Alignment.BottomEnd) {
                    ActionIcon(presentation.icon, presentation.text, useUnknownFallback = true)
                    Icon(AllIconsKeys.General.Dropdown, contentDescription = null)
                }
            }
        }
        if (expanded) {
            ActionMenu(
                group = group,
                onDismissRequest = { expanded = false },
                keepPopupsForToggles = keepPopupsForToggles,
            )
        }
    }
}
