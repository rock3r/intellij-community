// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.devkit.compose.demo

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware

/**
 * The actions behind the Swing-vs-Jewel comparison row. They are declared in `plugin.xml` like any other action, so the
 * comparison drives *one* IDE-registered action from both toolkits rather than two look-alike definitions: the Swing
 * toolbar resolves them through `ActionManager`, and the Jewel toolbar binds the same IDs, which the bridge registry
 * adopts.
 *
 * That is what makes the row prove something. Both sides read the same `update()`, so enablement and toggle state can
 * only ever agree — flip the toggle on either side and both redraw.
 */
internal object JewelComparisonActionIds {
    const val SAVE: String = "JewelComparison.Save"
    const val WORD_WRAP: String = "JewelComparison.WordWrap"
    const val DELETE: String = "JewelComparison.Delete"
}

/** Shared state the comparison actions report through `update()`; deliberately trivial, it only has to be observable. */
internal object JewelComparisonState {
    @Volatile var wordWrap: Boolean = false

    @Volatile var saveCount: Int = 0
}

internal class JewelComparisonSaveAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        JewelComparisonState.saveCount++
    }
}

/**
 * A toggle whose selected state lives outside the action, so both toolbars observe the same value. `ToggleAction.update`
 * writes it into the per-place presentation as `Toggleable.SELECTED_KEY`, which is precisely the client property the
 * bridge maps into Jewel's presentation.
 */
internal class JewelComparisonWordWrapAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = JewelComparisonState.wordWrap

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        JewelComparisonState.wordWrap = state
    }
}

/**
 * Disabled whenever word wrap is on, purely so the row demonstrates enablement crossing the bridge: turning the toggle
 * on greys this out in *both* toolbars, from one `update()`.
 */
internal class JewelComparisonDeleteAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !JewelComparisonState.wordWrap
    }

    override fun actionPerformed(e: AnActionEvent) = Unit
}
