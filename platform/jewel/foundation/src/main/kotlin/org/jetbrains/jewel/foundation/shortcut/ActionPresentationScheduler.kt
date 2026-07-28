// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.InternalJewelApi

/**
 * Demand-driven presentation sampling: controls never call an update from composition; they register demand for an
 * action ID and observe an equality-gated [StateFlow]. A poll re-resolves only the actions that currently have
 * collectors and publishes only when the sample differs — `StateFlow`'s conflation makes an unchanged poll invalidate
 * nothing, so no recomposition happens for unchanged state.
 *
 * The host decides when to poll: focus changes, dispatches, keymap edits, and explicit [invalidate] calls. There is
 * deliberately no internal timer; the IJPL bridge rides the platform's action-update cadence instead (bridge slice),
 * and standalone hosts poll on their own signals.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class ActionPresentationScheduler(private val resolve: (JewelActionId) -> ActionPresentation) {
    private class Entry(initial: ActionPresentation) {
        val flow = MutableStateFlow(initial)
        var demand: Int = 0
    }

    private val entries = ConcurrentHashMap<JewelActionId, Entry>()

    /**
     * Invoked after every [invalidate], so the owning host can keep a snapshot-state generation counter in step with
     * the scheduler's own signals without every call site having to bump both. Set by [JewelShortcutHostState]; the
     * bridge's off-thread invalidations gate the bump out on the host side.
     */
    internal var onInvalidated: (() -> Unit)? = null

    /** Registers demand and returns the equality-gated sample flow. Pair with [release]. */
    public fun acquire(actionId: JewelActionId): StateFlow<ActionPresentation> {
        val entry =
            entries.compute(actionId) { id, existing -> (existing ?: Entry(resolve(id))).also { it.demand++ } }!!
        return entry.flow.asStateFlow()
    }

    public fun release(actionId: JewelActionId) {
        entries.computeIfPresent(actionId) { _, entry ->
            entry.demand--
            if (entry.demand <= 0) null else entry
        }
    }

    /**
     * Re-samples one action (or all with active demand), publishes only changed samples, then runs [onInvalidated].
     *
     * This is the host/bridge presentation-cadence hook, **not** an app-author API. The IJPL bridge re-samples on the
     * platform's action-update timer, and the standalone host re-samples on structural signals (binding registration,
     * focus changes, dispatch). The standalone reactive path recomputes from the snapshot state its `update` blocks
     * read on its own, so app code never needs to call this.
     */
    @ApiStatus.Internal
    @InternalJewelApi
    public fun invalidate(actionId: JewelActionId? = null) {
        if (actionId != null) {
            entries[actionId]?.let { it.flow.value = resolve(actionId) }
        } else {
            for ((id, entry) in entries) entry.flow.value = resolve(id)
        }
        onInvalidated?.invoke()
    }

    public fun activeDemandCount(): Int = entries.size
}

/**
 * Collects this action's presentation while in composition, registering demand with [scheduler] and releasing it on
 * dispose. Recomposition occurs only when the sample changes by equality.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun JewelAction.collectPresentationAsState(scheduler: ActionPresentationScheduler): State<ActionPresentation> {
    val flow = remember(scheduler, id) { scheduler.acquire(id) }
    DisposableEffect(scheduler, id) { onDispose { scheduler.release(id) } }
    return flow.collectAsState()
}

/**
 * Collects one projection of this action's presentation, gated by the projection's own equality: a control observing
 * only `enabled` does not recompose when the text changes. The [selector] must be pure; the latest lambda is used
 * without restarting collection when the composable recomposes with a new instance.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun <T> JewelAction.collectPresentationAsState(
    scheduler: ActionPresentationScheduler,
    selector: (ActionPresentation) -> T,
): State<T> {
    val flow = remember(scheduler, id) { scheduler.acquire(id) }
    DisposableEffect(scheduler, id) { onDispose { scheduler.release(id) } }
    val currentSelector by rememberUpdatedState(selector)
    val projected = remember(flow) { flow.map { currentSelector(it) }.distinctUntilChanged() }
    return projected.collectAsState(initial = currentSelector(flow.value))
}
