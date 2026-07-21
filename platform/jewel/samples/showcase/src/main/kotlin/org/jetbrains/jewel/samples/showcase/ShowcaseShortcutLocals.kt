// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the
// Apache 2.0 license.
package org.jetbrains.jewel.samples.showcase

import androidx.compose.runtime.staticCompositionLocalOf
import org.jetbrains.jewel.foundation.shortcut.JewelActionRegistry
import org.jetbrains.jewel.foundation.shortcut.MutableJewelKeymap

/**
 * Demo-only seam for the Action Components page's live keymap editor.
 *
 * The action components themselves resolve everything through
 * [org.jetbrains.jewel.foundation.shortcut.LocalJewelShortcutHost], so they are host-agnostic and portable to any
 * surface (standalone window, DevKit tool window). The [org.jetbrains.jewel.ui.component.KeymapSettingsPanel], however,
 * needs the concrete registry and a *mutable* keymap — which the host deliberately does not expose (a bridge host's
 * keymap lives in the IDE, not as a `MutableJewelKeymap`).
 *
 * Rather than widen the host's public API for a demo affordance, the provider hands these down through these locals:
 * the standalone installs them next to the host at the window root, and a surface that does not provide them (the
 * DevKit tab, where the IDE owns keymap editing) simply gets `null` and renders no panel.
 */
public val LocalShowcaseKeymap: androidx.compose.runtime.ProvidableCompositionLocal<MutableJewelKeymap?> =
    staticCompositionLocalOf {
        null
    }

/** Companion of [LocalShowcaseKeymap]; see its docs. */
public val LocalShowcaseActionRegistry: androidx.compose.runtime.ProvidableCompositionLocal<JewelActionRegistry?> =
    staticCompositionLocalOf {
        null
    }
