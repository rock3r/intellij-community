package org.jetbrains.jewel.samples.standalone

import org.jetbrains.jewel.foundation.shortcut.DefaultJewelActionRegistry
import org.jetbrains.jewel.foundation.shortcut.InMemoryJewelKeymap
import org.jetbrains.jewel.foundation.shortcut.JewelShortcutHostState
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents
import org.jetbrains.jewel.samples.showcase.ShowcaseShortcuts
import org.jetbrains.skiko.hostOs

/**
 * The standalone sample's shortcut host: the action catalog, the (mutable, live-rebindable) keymap seeded from the
 * catalog's OS-appropriate defaults, and the per-window host state. The window installs
 * [JewelShortcutHostState.onPreviewKeyEvent] as its AWT-level pre-scene hook and
 * [JewelShortcutHostState.resolverRootModifier] at the content root; pages then declare commands with plain
 * `Modifier.shortcut` and never see host-specific types.
 */
public object ShowcaseShortcutHost {
    public val registry: DefaultJewelActionRegistry = DefaultJewelActionRegistry()

    init {
        ShowcaseShortcuts.definitions(useMacModifiers = hostOs.isMacOS).forEach(registry::register)
        // The Action Components page binds these; without them registered every control there resolves as
        // Unregistered, so it renders the unknown-action placeholder instead of the action's own presentation.
        ShowcaseActionComponents.definitions(useMacModifiers = hostOs.isMacOS).forEach(registry::register)
    }

    public val keymap: InMemoryJewelKeymap = InMemoryJewelKeymap.fromDefaults("showcase", registry)

    public val state: JewelShortcutHostState = JewelShortcutHostState(registry) { keymap }
}
