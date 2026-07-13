// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/**
 * Whether performing an action from an open menu dismisses the menu, mirroring the four states of IJPL's
 * `KeepPopupOnPerform` as *presentation* state — popup retention is dynamic in IJPL (a mutable `Presentation` property
 * interacting with the keep-popups-for-toggles preference), so a static descriptor field cannot round-trip adapted IJPL
 * groups.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public enum class MenuDismissPolicy {
    /** Always dismiss on perform; the default for command actions. */
    Dismiss,

    /** Dismiss unless the user explicitly requests keeping the menu (e.g. via a keyboard modifier). */
    KeepIfRequested,

    /** Keep the menu when the UI prefers keeping popups for toggles; the default for toggle actions. */
    KeepIfPreferred,

    /** Never dismiss on perform; for toggles that modify their own menu. */
    KeepAlways,
}

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
 * An immutable sample of an action's renderable state. `enabled` gates execution; `visible` gates rendering only — a
 * hidden but enabled action stays keymap-invocable, matching IJPL semantics.
 *
 * [icon] is host-interpreted: the foundation deliberately has no icon type, so components render the representations
 * they understand and skip the rest. Jewel UI components render `org.jetbrains.jewel.ui.icon.IconKey`; the IJPL bridge
 * may surface a platform `javax.swing.Icon` sampled from the mapped action. Values must have meaningful equality —
 * presentation flows are equality-gated.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class ActionPresentation(
    val text: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val selected: Boolean = false,
    val icon: Any? = null,
    val menuDismissPolicy: MenuDismissPolicy? = null,
    val resolution: ActionResolution = ActionResolution.Unregistered,
) {
    public companion object {
        /** What action-bound components render when no shortcut host is installed in the composition. */
        public fun hostUnavailable(action: JewelAction): ActionPresentation =
            ActionPresentation(text = action.title, enabled = false, resolution = ActionResolution.HostUnavailable)
    }
}

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
    val icon: PresentationValue<Any?> = PresentationValue.Inherit,
    val menuDismissPolicy: PresentationValue<MenuDismissPolicy?> = PresentationValue.Inherit,
) {
    public fun mergeOver(base: ActionPresentation): ActionPresentation =
        base.copy(
            text = (text as? PresentationValue.Set)?.value ?: base.text,
            description = if (description is PresentationValue.Set) description.value else base.description,
            visible = (visible as? PresentationValue.Set)?.value ?: base.visible,
            selected = (selected as? PresentationValue.Set)?.value ?: base.selected,
            icon = if (icon is PresentationValue.Set) icon.value else base.icon,
            menuDismissPolicy =
                if (menuDismissPolicy is PresentationValue.Set) menuDismissPolicy.value else base.menuDismissPolicy,
        )

    public companion object {
        public val Empty: ActionPresentationOverride = ActionPresentationOverride()
    }
}
