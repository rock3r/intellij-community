// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

@ApiStatus.Experimental
@ExperimentalJewelApi
@JvmInline
public value class JewelActionGroupId(public val value: String)

/** Typed key into the focused-host [ActionContext] consumed during group expansion. */
@ApiStatus.Experimental @ExperimentalJewelApi public interface ActionContextKey<T>

/** Focused-host context a group expands against; stable for the duration of one expansion. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public interface ActionContext {
    public val hostId: Any

    public operator fun <T> get(key: ActionContextKey<T>): T?

    public companion object {
        public val Empty: ActionContext =
            object : ActionContext {
                override val hostId: Any = Unit

                override fun <T> get(key: ActionContextKey<T>): T? = null
            }
    }
}

/** Static descriptor metadata in this slice; context-dependent group presentation is deferred. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class ActionGroupPresentation(val text: String, val visible: Boolean = true, val popup: Boolean = false)

/**
 * A dynamic content provider for menus and toolbars. Deliberately separate from shortcut dispatch: only leaf actions
 * have keymap bindings and focused handlers; a group is never an invokable leaf.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public interface JewelActionGroup {
    public val id: JewelActionGroupId
    public val presentation: ActionGroupPresentation

    public fun children(context: ActionContext): List<JewelMenuEntry>
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
    override fun children(context: ActionContext): List<JewelMenuEntry> = entries
}
