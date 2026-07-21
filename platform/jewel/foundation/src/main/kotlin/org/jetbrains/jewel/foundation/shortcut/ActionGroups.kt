// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import com.intellij.platform.icons.Icon
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

@ApiStatus.Experimental
@ExperimentalJewelApi
@JvmInline
public value class JewelActionGroupId(public val value: String)

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
    val text: String,
    val visible: Boolean = true,
    val popup: Boolean = false,
    val icon: Icon? = null,
)

/**
 * A dynamic content provider for menus and toolbars. Deliberately separate from shortcut dispatch: only leaf actions
 * have keymap bindings and focused handlers; a group is never an invokable leaf.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public interface JewelActionGroup {
    public val id: JewelActionGroupId
    public val presentation: ActionGroupPresentation

    public fun children(): List<JewelMenuEntry>
}

@ApiStatus.Experimental
@ExperimentalJewelApi
public enum class ActionPlacement {
    Primary,
    Secondary,
}

@ApiStatus.Experimental
@ExperimentalJewelApi
public sealed interface JewelMenuEntry {
    public data class Action(val action: JewelAction, val placement: ActionPlacement = ActionPlacement.Primary) :
        JewelMenuEntry

    public data class Group(val group: JewelActionGroup) : JewelMenuEntry

    public data class Separator(val text: String? = null) : JewelMenuEntry
}

/** Simple static group for application-defined menus/toolbars. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class StaticJewelActionGroup(
    override val id: JewelActionGroupId,
    override val presentation: ActionGroupPresentation,
    private val entries: List<JewelMenuEntry>,
) : JewelActionGroup {
    override fun children(): List<JewelMenuEntry> = entries
}
