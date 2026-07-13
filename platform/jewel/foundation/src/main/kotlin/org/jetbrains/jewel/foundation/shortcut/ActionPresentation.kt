// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/** Why a presentation sample looks the way it does; lets controls distinguish failure modes. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public enum class ActionResolution {
    Resolved,
    Unregistered,
    NoFocusedBinding,
    HostUnavailable,
}

/**
 * An immutable sample of an action's renderable state. `enabled` gates execution; `visible` gates
 * rendering only — a hidden but enabled action stays keymap-invocable, matching IJPL semantics.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class ActionPresentation(
    val text: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val selected: Boolean = false,
    val resolution: ActionResolution = ActionResolution.Unregistered,
)

/** Tri-state override value: inherit the template value, or set (possibly to null for nullables). */
@ApiStatus.Experimental
@ExperimentalJewelApi
public sealed interface PresentationValue<out T> {
    public data object Inherit : PresentationValue<Nothing>

    public data class Set<T>(val value: T) : PresentationValue<T>
}

/** Per-binding presentation overrides merged over the action's template presentation. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class ActionPresentationOverride(
    val text: PresentationValue<String> = PresentationValue.Inherit,
    val description: PresentationValue<String?> = PresentationValue.Inherit,
    val visible: PresentationValue<Boolean> = PresentationValue.Inherit,
    val selected: PresentationValue<Boolean> = PresentationValue.Inherit,
) {
    public fun mergeOver(base: ActionPresentation): ActionPresentation =
        base.copy(
            text = (text as? PresentationValue.Set)?.value ?: base.text,
            description =
                if (description is PresentationValue.Set) description.value else base.description,
            visible = (visible as? PresentationValue.Set)?.value ?: base.visible,
            selected = (selected as? PresentationValue.Set)?.value ?: base.selected,
        )

    public companion object {
        public val Empty: ActionPresentationOverride = ActionPresentationOverride()
    }
}
