// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/**
 * Observes this action's presentation for [host], the way action-bound components do — prefer this host overload over
 * the [ActionPresentationScheduler] one, because it lets the host choose how presentation is derived.
 * - Standalone ([JewelShortcutHostState.reactivePresentation], the default): a `derivedStateOf` over the focused
 *   bindings and the live `Modifier.provideData` values. The control recomposes exactly when the snapshot state its
 *   binding's `update` block reads changes — no manual invalidation, and a hot unrelated datum recomputes nothing.
 * - IJPL bridge: the demand-driven [ActionPresentationScheduler], sampled on the platform's action-update cadence,
 *   because the bridge wraps the platform `DataContext`, which cannot be observed as snapshot state.
 *
 * Recomposition is equality-gated either way: an unchanged presentation does not recompose the control.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun JewelAction.collectPresentationAsState(host: JewelShortcutHostState): State<ActionPresentation> {
    if (!host.reactivePresentation) return collectPresentationAsState(host.presentations)
    val actionId = id
    return remember(host, actionId) { derivedStateOf { host.reactivePresentationOf(actionId) } }
}

/**
 * Observes one projection of this action's presentation for [host], gated by the projection's own equality: a control
 * observing only `enabled` does not recompose when the text changes. The [selector] must be pure; the latest lambda is
 * used without restarting observation when the composable recomposes with a new instance.
 *
 * The reactive standalone path derives the projection inside the same `derivedStateOf`, so per-key precision and
 * equality-gating both hold; the bridge path delegates to the scheduler's selector overload.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun <T> JewelAction.collectPresentationAsState(
    host: JewelShortcutHostState,
    selector: (ActionPresentation) -> T,
): State<T> {
    if (!host.reactivePresentation) return collectPresentationAsState(host.presentations, selector)
    val actionId = id
    val currentSelector by rememberUpdatedState(selector)
    return remember(host, actionId) { derivedStateOf { currentSelector(host.reactivePresentationOf(actionId)) } }
}
