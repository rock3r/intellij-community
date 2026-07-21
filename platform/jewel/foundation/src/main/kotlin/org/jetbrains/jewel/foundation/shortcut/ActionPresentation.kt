// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import com.intellij.platform.icons.Icon
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
 * Names of presentation properties Jewel and the IJPL bridge both understand. They are plain strings because IJPL keys
 * its client properties by `Key`, whose `toString()` is the name — matching on the name is what lets a presentation
 * round-trip the bridge without either side owning the other's key objects.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public object ActionPropertyNames {
    /**
     * Mirrors IJPL `Toggleable.SELECTED_KEY`: whether a toggle action is currently on. Read it through the
     * [ActionPresentation.selected] extension rather than by name.
     */
    public const val Selected: String = "selected"

    /**
     * Mirrors IJPL `ActionUtil.SHOW_TEXT_IN_TOOLBAR`: render the action's text in a toolbar rather than its icon alone.
     * Per-action, not per-toolbar — the same switch IJPL uses to pick `ActionButtonWithText` over `ActionButton`.
     */
    public const val ShowTextInToolbar: String = "SHOW_TEXT_IN_TOOLBAR"

    /** Mirrors IJPL `ActionUtil.HIDE_DROPDOWN_ICON`: suppress the dropdown affordance on a popup group's button. */
    public const val HideDropdownIcon: String = "HIDE_DROPDOWN_ICON"
}

/**
 * The action's authored defaults — IJPL's *template presentation*, the values declared once (in `plugin.xml` or an
 * `AnAction` constructor) and shared by every place the action appears.
 *
 * It is deliberately separate from [ActionPresentation]: the template carries no `enabled`/`visible`, because those are
 * per-place state that only [JewelAction.update]-equivalent sampling produces. IJPL enforces the same split — its
 * template presentation asserts that enablement and visibility are never written to it, since menus and shortcut
 * processing use different defaults.
 *
 * The template is also a live fallback, not just a seed: a control whose sampled [ActionPresentation.icon] is null
 * falls back to [icon] here before giving up, exactly as `ActionButton.getFallbackIcon` consults
 * `getAction().getTemplatePresentation()`.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class ActionTemplatePresentation(
    val text: String,
    val description: String? = null,
    val icon: Icon? = null,
    val properties: Map<String, Any> = emptyMap(),
)

/**
 * An immutable sample of one action's renderable state for one place — the Compose analogue of the per-place
 * `Presentation` IJPL clones from the template and mutates in `update()`. `enabled` gates execution; `visible` gates
 * rendering only — a hidden but enabled action stays keymap-invocable, matching IJPL semantics.
 *
 * Where IJPL preserves the *object identity* of a per-place presentation so its `PropertyChangeListener`s keep firing,
 * Jewel samples immutable values and preserves the identity of the `StateFlow` instead; equality of these values is
 * what gates recomposition, so every field must have meaningful equality — including [properties] and [icon] (icon
 * descriptors are data, and compare by value).
 *
 * [properties] mirrors IJPL's presentation client properties, the extension point that carries state with no
 * first-class field: without it, anything a platform action's `update()` writes would be dropped crossing the bridge.
 * See [ActionPropertyNames] for the names both sides understand.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class ActionPresentation(
    val text: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val icon: Icon? = null,
    val menuDismissPolicy: MenuDismissPolicy? = null,
    val properties: Map<String, Any> = emptyMap(),
    val resolution: ActionResolution = ActionResolution.Unregistered,
) {
    /**
     * Reads a boolean presentation property, absent meaning `false` — the semantics of IJPL's client-property flags.
     */
    public fun isFlagSet(name: String): Boolean = properties[name] == true

    /** Returns a copy with one property set, the value-level equivalent of `Presentation.putClientProperty`. */
    public fun withProperty(name: String, value: Any): ActionPresentation =
        copy(properties = properties + (name to value))

    public companion object {
        /** What action-bound components render when no shortcut host is installed in the composition. */
        public fun hostUnavailable(action: JewelAction): ActionPresentation =
            action.template.sampled(enabled = false, resolution = ActionResolution.HostUnavailable)
    }
}

/**
 * Whether a toggle action is currently on.
 *
 * State that IJPL keeps as a presentation *client property* stays a client property here too, read through an extension
 * rather than promoted to a field: one storage location means the map and a field can never disagree, and it is what
 * lets the bridge copy properties across verbatim instead of translating each known key by hand. [enabled],
 * [visible][ActionPresentation.visible] and text are fields precisely because IJPL models *those* as real
 * `Presentation` fields.
 */
@get:ApiStatus.Experimental
@ExperimentalJewelApi
public val ActionPresentation.selected: Boolean
    get() = isFlagSet(ActionPropertyNames.Selected)

/** Whether this action asks a toolbar to show its text rather than its icon alone. */
@get:ApiStatus.Experimental
@ExperimentalJewelApi
public val ActionPresentation.showsTextInToolbar: Boolean
    get() = isFlagSet(ActionPropertyNames.ShowTextInToolbar)

/** Whether a popup group asks to suppress its dropdown affordance. */
@get:ApiStatus.Experimental
@ExperimentalJewelApi
public val ActionPresentation.hidesDropdownIcon: Boolean
    get() = isFlagSet(ActionPropertyNames.HideDropdownIcon)

/** Derives a per-place sample from the authored defaults, the starting point every override merges over. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public fun ActionTemplatePresentation.sampled(enabled: Boolean, resolution: ActionResolution): ActionPresentation =
    ActionPresentation(
        text = text,
        description = description,
        enabled = enabled,
        icon = icon,
        properties = properties,
        resolution = resolution,
    )

/** Tri-state override value: inherit the template value, or set (possibly to null for nullables). */
@ApiStatus.Experimental
@ExperimentalJewelApi
public sealed interface PresentationValue<out T> {
    public data object Inherit : PresentationValue<Nothing>

    public data class Set<T>(val value: T) : PresentationValue<T>
}

/**
 * What one binding changes about its action's presentation for the place it occupies — the declarative stand-in for the
 * mutations an IJPL `update()` performs on a per-place `Presentation`.
 *
 * [properties] merges key-by-key over the base rather than replacing the map wholesale, matching
 * `Presentation.copyFrom`, so a binding overriding one flag does not erase the template's others.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class ActionPresentationOverride(
    val text: PresentationValue<String> = PresentationValue.Inherit,
    val description: PresentationValue<String?> = PresentationValue.Inherit,
    val visible: PresentationValue<Boolean> = PresentationValue.Inherit,
    val selected: PresentationValue<Boolean> = PresentationValue.Inherit,
    val icon: PresentationValue<Icon?> = PresentationValue.Inherit,
    val menuDismissPolicy: PresentationValue<MenuDismissPolicy?> = PresentationValue.Inherit,
    val properties: Map<String, Any> = emptyMap(),
) {
    public fun mergeOver(base: ActionPresentation): ActionPresentation {
        val selectedOverride = selected
        val mergedProperties = buildMap {
            putAll(base.properties)
            putAll(properties)
            // `selected` is sugar over the property of the same name: overriding it writes the property,
            // so there is only ever one place the toggle state lives.
            if (selectedOverride is PresentationValue.Set) {
                put(ActionPropertyNames.Selected, selectedOverride.value)
            }
        }
        return base.copy(
            text = (text as? PresentationValue.Set)?.value ?: base.text,
            description = if (description is PresentationValue.Set) description.value else base.description,
            visible = (visible as? PresentationValue.Set)?.value ?: base.visible,
            icon = if (icon is PresentationValue.Set) icon.value else base.icon,
            menuDismissPolicy =
                if (menuDismissPolicy is PresentationValue.Set) menuDismissPolicy.value else base.menuDismissPolicy,
            properties = mergedProperties,
        )
    }

    public companion object {
        public val Empty: ActionPresentationOverride = ActionPresentationOverride()
    }
}
