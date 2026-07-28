// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the
// Apache 2.0 license.
package org.jetbrains.jewel.samples.showcase

import androidx.compose.ui.input.key.Key
import com.intellij.platform.icons.Icon
import com.intellij.platform.icons.icon
import org.jetbrains.jewel.foundation.shortcut.ActionContextKey
import org.jetbrains.jewel.foundation.shortcut.ActionGroupPresentation
import org.jetbrains.jewel.foundation.shortcut.ActionPropertyNames
import org.jetbrains.jewel.foundation.shortcut.ActionTemplatePresentation
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionDefinition
import org.jetbrains.jewel.foundation.shortcut.JewelActionGroupId
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.jetbrains.jewel.foundation.shortcut.JewelActionKind
import org.jetbrains.jewel.foundation.shortcut.JewelKeySequence
import org.jetbrains.jewel.foundation.shortcut.JewelKeyStroke
import org.jetbrains.jewel.foundation.shortcut.JewelMenuEntry
import org.jetbrains.jewel.foundation.shortcut.StaticJewelActionGroup
import org.jetbrains.jewel.ui.icon.iconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The action catalog behind the Action Components page: a handful of commands and toggles, plus the group structures
 * the toolbar and menu components render (separators, an inline subgroup that flattens into its parent, and a popup
 * subgroup that becomes a submenu).
 *
 * Actions are declared once here and bound where the demo UI lives, exactly as an application would: the components
 * never take handlers, they resolve the focused binding through the host.
 */
public object ShowcaseActionComponents {
    /**
     * Icons are built lazily: an icon descriptor is created through the [com.intellij.platform.icons.IconManager],
     * which the theme installs, so building them at object-initialisation time would run before there is one.
     */
    private fun actionIcon(key: org.jetbrains.jewel.ui.icon.IconKey): Icon = icon { iconKey(key) }

    /**
     * Whether the demo surface currently has a selection. Provided into the action context with `Modifier.provideData`
     * and read by [Delete]'s context-driven enablement, so the button (and its shortcut) enable exactly when a real
     * datum says they should — the same shape a platform action reads from its `DataContext`.
     */
    public val HasSelection: ActionContextKey<Boolean> =
        ActionContextKey.create("org.jetbrains.jewel.showcase.hasSelection")

    public val Save: JewelAction by lazy {
        JewelAction(
            JewelActionId("org.jetbrains.jewel.showcase.components.save"),
            "Save",
            icon = actionIcon(AllIconsKeys.Actions.MenuSaveall),
            description = "Save the current document",
        )
    }
    public val Refresh: JewelAction by lazy {
        JewelAction(
            JewelActionId("org.jetbrains.jewel.showcase.components.refresh"),
            "Refresh",
            icon = actionIcon(AllIconsKeys.Actions.Refresh),
        )
    }
    public val Delete: JewelAction by lazy {
        JewelAction(
            JewelActionId("org.jetbrains.jewel.showcase.components.delete"),
            "Delete",
            icon = actionIcon(AllIconsKeys.Actions.GC),
        )
    }

    /** Bound but deliberately overridden to hidden, to show `respectVisibility` and the keymap-still-invocable rule. */
    public val Archive: JewelAction by lazy {
        JewelAction(
            JewelActionId("org.jetbrains.jewel.showcase.components.archive"),
            "Archive",
            icon = actionIcon(AllIconsKeys.Actions.Rollback),
        )
    }

    /** Never bound anywhere: renders disabled (the NoFocusedBinding presentation row). */
    public val Unavailable: JewelAction by lazy {
        JewelAction(
            JewelActionId("org.jetbrains.jewel.showcase.components.unavailable"),
            "Unavailable",
            icon = actionIcon(AllIconsKeys.Actions.Edit),
        )
    }

    /**
     * Deliberately icon-less, to exercise the fallback Swing applies: an icon-only button substitutes the
     * unknown-action placeholder rather than rendering nothing.
     */
    public val NoIcon: JewelAction by lazy {
        JewelAction(JewelActionId("org.jetbrains.jewel.showcase.components.noIcon"), "No icon")
    }

    /**
     * Renders its label instead of an icon, the way an action opting into `SHOW_TEXT_IN_TOOLBAR` becomes an
     * `ActionButtonWithText` in a Swing toolbar. It carries no icon, so no gutter is reserved for one.
     */
    public val TextAction: JewelAction by lazy {
        JewelAction(
            JewelActionId("org.jetbrains.jewel.showcase.components.textAction"),
            ActionTemplatePresentation(
                text = "Commit",
                description = "Commit the staged changes",
                properties = mapOf(ActionPropertyNames.ShowTextInToolbar to true),
            ),
        )
    }

    public val WordWrap: JewelAction by lazy {
        JewelAction(
            JewelActionId("org.jetbrains.jewel.showcase.components.wordWrap"),
            "Word wrap",
            kind = JewelActionKind.Toggle,
            icon = actionIcon(AllIconsKeys.Actions.ToggleSoftWrap),
        )
    }
    public val ShowWhitespace: JewelAction by lazy {
        JewelAction(
            JewelActionId("org.jetbrains.jewel.showcase.components.showWhitespace"),
            "Show whitespace",
            kind = JewelActionKind.Toggle,
            icon = actionIcon(AllIconsKeys.Actions.InlayGear),
        )
    }

    /** Inline subgroup: a toolbar flattens it in place, a menu renders it as a nested section. */
    public val EditingGroup: StaticJewelActionGroup by lazy {
        StaticJewelActionGroup(
            JewelActionGroupId("org.jetbrains.jewel.showcase.components.editing"),
            ActionGroupPresentation(text = "Editing"),
            listOf(JewelMenuEntry.Action(WordWrap), JewelMenuEntry.Action(ShowWhitespace)),
        )
    }

    /** Popup subgroup: a toolbar renders it as a menu button, a menu as a submenu. */
    public val MoreGroup: StaticJewelActionGroup by lazy {
        StaticJewelActionGroup(
            JewelActionGroupId("org.jetbrains.jewel.showcase.components.more"),
            ActionGroupPresentation(text = "More", popup = true, icon = actionIcon(AllIconsKeys.Actions.More)),
            listOf(JewelMenuEntry.Action(Archive), JewelMenuEntry.Action(Delete)),
        )
    }

    /** The toolbar/menu group: leaf actions, a separator, an inline subgroup, and a popup subgroup. */
    public val MainGroup: StaticJewelActionGroup by lazy {
        StaticJewelActionGroup(
            JewelActionGroupId("org.jetbrains.jewel.showcase.components.main"),
            ActionGroupPresentation(text = "Actions"),
            listOf(
                JewelMenuEntry.Action(Save),
                JewelMenuEntry.Action(Refresh),
                JewelMenuEntry.Separator(),
                JewelMenuEntry.Group(EditingGroup),
                JewelMenuEntry.Separator(),
                JewelMenuEntry.Group(MoreGroup),
            ),
        )
    }

    /** The menu opened by the split button's chevron segment. */
    public val RunOptionsGroup: StaticJewelActionGroup by lazy {
        StaticJewelActionGroup(
            JewelActionGroupId("org.jetbrains.jewel.showcase.components.runOptions"),
            ActionGroupPresentation(text = "Run options", popup = true),
            listOf(JewelMenuEntry.Action(Refresh), JewelMenuEntry.Separator(), JewelMenuEntry.Action(Delete)),
        )
    }

    /** Catalog for the host registry, with default bindings so the keymap panel has rows to rebind. */
    public fun definitions(useMacModifiers: Boolean): List<JewelActionDefinition> {
        fun primary(key: Key) =
            if (useMacModifiers) JewelKeyStroke(key, meta = true) else JewelKeyStroke(key, ctrl = true)
        return listOf(
            JewelActionDefinition(Save, listOf(JewelKeySequence(primary(Key.S)))),
            JewelActionDefinition(Refresh, listOf(JewelKeySequence(primary(Key.R)))),
            JewelActionDefinition(Delete, listOf(JewelKeySequence(JewelKeyStroke(Key.Delete)))),
            JewelActionDefinition(Archive, emptyList()),
            JewelActionDefinition(Unavailable, emptyList()),
            JewelActionDefinition(NoIcon, listOf(JewelKeySequence(primary(Key.N)))),
            JewelActionDefinition(TextAction, listOf(JewelKeySequence(primary(Key.K)))),
            JewelActionDefinition(WordWrap, listOf(JewelKeySequence(primary(Key.W)))),
            JewelActionDefinition(ShowWhitespace, emptyList()),
        )
    }
}
