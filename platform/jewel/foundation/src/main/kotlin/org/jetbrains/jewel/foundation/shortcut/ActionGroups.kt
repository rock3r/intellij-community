// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import com.intellij.platform.icons.IconDescriptor
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/** Stable identifier for a [JewelActionGroup]. */
@ApiStatus.Experimental
@ExperimentalJewelApi
@JvmInline
public value class JewelActionGroupId(
    /** String identifier of the action group. */
    public val value: String
)

/**
 * Static descriptor metadata in this slice; context-dependent group presentation is deferred.
 *
 * A group carries an [icon] because in IJPL an `ActionGroup` *is* an `AnAction` and so has a presentation like any
 * other: a popup group placed directly in a toolbar renders as an ordinary action button, and its icon is what the
 * dropdown badge is drawn over.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class ActionGroupPresentation(
    /** Display label of the group. */
    val text: String,
    /** Whether the group should be shown in toolbars and menus. */
    val visible: Boolean = true,
    /** Whether the group opens a popup menu rather than expanding inline. */
    val popup: Boolean = false,
    /** Optional icon descriptor rendered for popup group buttons or submenus. */
    val icon: IconDescriptor? = null,
)

/**
 * A dynamic content provider for menus and toolbars. Deliberately separate from shortcut dispatch: only leaf actions
 * have keymap bindings and focused handlers; a group is never an invokable leaf.
 *
 * [children] is expanded against the focused surface's [ActionContext], mirroring how an IJPL `ActionGroup.getChildren`
 * sees the invoking `DataContext`: a static group ignores it, a dynamic one reads it to decide what to show.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public interface JewelActionGroup {
    /** Unique identifier of this action group. */
    public val id: JewelActionGroupId

    /** Static presentation metadata for this action group. */
    public val presentation: ActionGroupPresentation

    /** Returns the ordered entries contained in this group for [context]. */
    public fun children(context: ActionContext): List<JewelMenuEntry>
}

/** Placement hint for an action entry within a toolbar or menu. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public enum class ActionPlacement {
    /** Primary visible placement in a toolbar or menu. */
    Primary,

    /** Overflow or secondary placement in a toolbar or menu. */
    Secondary,
}

/** One entry produced by a [JewelActionGroup]. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public sealed interface JewelMenuEntry {
    /** Leaf action entry with an optional [placement] hint. */
    public data class Action(
        /** Bound action represented by this entry. */
        val action: JewelAction,
        /** Placement preference for the action. */
        val placement: ActionPlacement = ActionPlacement.Primary,
    ) : JewelMenuEntry

    /** Nested subgroup entry, expanded inline or shown as a submenu. */
    public data class Group(
        /** Child action group referenced by this entry. */
        val group: JewelActionGroup
    ) : JewelMenuEntry

    /** Visual separator entry with an optional header [text]. */
    public data class Separator(
        /** Optional section label shown on the separator. */
        val text: String? = null
    ) : JewelMenuEntry
}

/** Simple static group for application-defined menus/toolbars. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class StaticJewelActionGroup(
    override val id: JewelActionGroupId,
    override val presentation: ActionGroupPresentation,
    private val entries: List<JewelMenuEntry>,
) : JewelActionGroup {
    override fun children(context: ActionContext): List<JewelMenuEntry> = entries
}
