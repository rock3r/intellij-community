// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/**
 * Semantics published by the shortcut modifiers, so UI tests and tooling can discover keyboard behavior declaratively:
 * - [JewelShortcutActions] — the action IDs bound on a node via `Modifier.shortcut` (multiple modifiers on one chain
 *   accumulate);
 * - [JewelClaimedShortcuts] — the display texts of sequences claimed via `Modifier.claimShortcut`.
 *
 * Match them in tests with `SemanticsMatcher.expectValue(JewelShortcutActions, listOf("my.action"))` or a keyed lookup;
 * combined with scene key injection (`performKeyInput`), a plain Compose UI test can both find shortcut-bearing nodes
 * and verify they dispatch.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public val JewelShortcutActions: SemanticsPropertyKey<List<String>> =
    SemanticsPropertyKey("JewelShortcutActions") { parent, child -> (parent ?: emptyList()) + child }

@ApiStatus.Experimental
@ExperimentalJewelApi
public val JewelClaimedShortcuts: SemanticsPropertyKey<List<String>> =
    SemanticsPropertyKey("JewelClaimedShortcuts") { parent, child -> (parent ?: emptyList()) + child }

@ApiStatus.Experimental
@ExperimentalJewelApi
public fun SemanticsPropertyReceiver.jewelShortcutActions(actionIds: List<String>) {
    this[JewelShortcutActions] = actionIds
}

@ApiStatus.Experimental
@ExperimentalJewelApi
public fun SemanticsPropertyReceiver.jewelClaimedShortcuts(displayTexts: List<String>) {
    this[JewelClaimedShortcuts] = displayTexts
}
