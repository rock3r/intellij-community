// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.actionSystem

import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import com.intellij.ide.CopyProvider
import com.intellij.ide.CutProvider
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import org.jetbrains.annotations.ApiStatus.Internal
import org.jetbrains.jewel.bridge.ComposeSemanticsTreeUtils.findFocusedComponent
import org.jetbrains.jewel.foundation.InternalJewelApi

/**
 * Copy/Cut counterparts of [ComposePasteProvider], completing the platform edit-action bridge: the
 * existing IDE actions remain the integration point and enable/perform against the focused Compose
 * node's semantics, so `$Copy`/`$Cut` in menus and keymaps work inside Jewel content without any
 * Jewel-specific action registration.
 */
private fun <T : Function<Boolean>> resolveSemanticsAction(
    dataContext: DataContext,
    key: SemanticsPropertyKey<AccessibilityAction<T>>,
): AccessibilityAction<T>? {
    val contextComponent = dataContext.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
    val composePanel = contextComponent?.parent as? ComposePanel ?: return null
    val focused = composePanel.findFocusedComponent() ?: return null
    return focused.config.getOrNull(key)
}

@Internal
@InternalJewelApi
public class ComposeCopyProvider : CopyProvider {
    override fun performCopy(dataContext: DataContext) {
        resolveSemanticsAction(dataContext, SemanticsActions.CopyText)?.action?.invoke()
    }

    override fun isCopyEnabled(dataContext: DataContext): Boolean =
        resolveSemanticsAction(dataContext, SemanticsActions.CopyText)?.action != null

    override fun isCopyVisible(dataContext: DataContext): Boolean = isCopyEnabled(dataContext)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

@Internal
@InternalJewelApi
public class ComposeCutProvider : CutProvider {
    override fun performCut(dataContext: DataContext) {
        resolveSemanticsAction(dataContext, SemanticsActions.CutText)?.action?.invoke()
    }

    override fun isCutEnabled(dataContext: DataContext): Boolean =
        resolveSemanticsAction(dataContext, SemanticsActions.CutText)?.action != null

    override fun isCutVisible(dataContext: DataContext): Boolean = isCutEnabled(dataContext)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
