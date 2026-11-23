// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.devkit.compose.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import org.jetbrains.jewel.foundation.shortcut.ActionGroupPresentation
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionGroupId
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.jetbrains.jewel.foundation.shortcut.JewelActionKind
import org.jetbrains.jewel.foundation.shortcut.JewelMenuEntry
import org.jetbrains.jewel.foundation.shortcut.LocalJewelShortcutHost
import org.jetbrains.jewel.foundation.shortcut.StaticJewelActionGroup
import org.jetbrains.jewel.ui.component.ActionToolbar
import org.jetbrains.jewel.ui.component.Text

/**
 * The Jewel half of the action-components comparison, bound to the *same* IDE-declared actions the Swing toolbar next
 * to it renders (see [JewelComparisonActionIds]).
 *
 * Nothing here declares a presentation. The action IDs are all this side knows; text, description, icon, enablement and
 * toggle state arrive from the platform's own `AnAction.update()`, adopted by the bridge's `JewelBridgeActionRegistry`
 * and sampled on the platform's update cadence. That is what makes the two toolbars impossible to disagree: flipping
 * *Word Wrap* on either side redraws both, and it also disables *Delete* on both, because there is one `update()`.
 *
 * It deliberately does **not** install a shortcut host. The panel's own host is the one the wrapper publishes to the
 * platform, so a nested host would take the bindings out of the platform's reach — the components would render, but the
 * keymap could no longer resolve their handlers.
 */
@Composable
internal fun JewelActionComponentsComparison() {
    val host = LocalJewelShortcutHost.current
    if (host == null) {
        Text("No Jewel shortcut host — the comparison needs a bridge Compose panel.")
        return
    }

    // Templates are intentionally bare: the registry adopts the declared action and supplies the real presentation.
    val save = remember { JewelAction(JewelActionId(JewelComparisonActionIds.SAVE), "Save") }
    val wordWrap =
        remember {
            JewelAction(
                JewelActionId(JewelComparisonActionIds.WORD_WRAP),
                "Word Wrap",
                kind = JewelActionKind.Toggle,
            )
        }
    val delete = remember { JewelAction(JewelActionId(JewelComparisonActionIds.DELETE), "Delete") }

    val group =
        remember(save, wordWrap, delete) {
            StaticJewelActionGroup(
                JewelActionGroupId("com.intellij.devkit.compose.comparison.main"),
                ActionGroupPresentation(text = "Actions"),
                listOf(
                    JewelMenuEntry.Action(save),
                    JewelMenuEntry.Action(wordWrap),
                    JewelMenuEntry.Separator(),
                    JewelMenuEntry.Action(delete),
                ),
            )
        }

    // Presentation sampling is demand-driven; the bridge invalidates it on the platform's action timer, which is the
    // same cadence Swing toolbars refresh at. Invalidating once on entry keeps the first frame honest.
    DisposableEffect(host) {
        host.presentations.invalidate()
        onDispose {}
    }

    ActionToolbar(group)
}
