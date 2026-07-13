// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/** A focused command binding as seen by the engine; innermost bindings come LAST in the list. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class EngineBinding(
    public val actionId: JewelActionId,
    public val enabled: Boolean,
    public val blocksOuterBindings: Boolean,
    public val origin: String,
    public val onInvoke: () -> Unit,
)

/** A focused sequence claim as seen by the engine; innermost claims come LAST in the list. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class EngineClaim(
    public val sequence: JewelKeySequence,
    public val enabled: Boolean,
    public val blocksOuterClaims: Boolean,
    public val onInvoke: () -> Unit,
)

@ApiStatus.Experimental
@ExperimentalJewelApi
public sealed interface DispatchDecision {
    /** The event was consumed by Jewel dispatch. */
    public data class Consumed(
        public val invokedActionId: JewelActionId?,
        public val invokedSequence: JewelKeySequence?,
        public val route: Route,
    ) : DispatchDecision {
        public enum class Route {
            Claim,
            Keymap,
            ChordPrefix,
            ChordCancelled,
            TypedSuppressed,
        }
    }

    /** Not Jewel's event: fall through to ordinary input handling. */
    public data object Pass : DispatchDecision
}

/**
 * The host-neutral dispatch state machine implementing the PRD's standalone/claim contract:
 * 1. focused claims resolve before keymap lookup;
 * 2. an in-flight two-stroke sequence completes or cancels-and-consumes;
 * 3. an exact one-stroke command wins immediately over entering a chord with the same first stroke;
 * 4. a chord prefix is consumed and enters pending state;
 * 5. anything else passes through, and an unbound key is never swallowed.
 *
 * Additional contract points pinned by tests:
 * - modifier-only key-downs never participate in matching (callers pass strokes via
 *   [JewelKeyStroke.fromKeyDownOrNull]);
 * - any consumed key-down arms suppression of the trailing KEY_TYPED event, so claimed printable keys do
 *   not leak characters into focused text fields;
 * - the nearest (innermost) focused enabled binding wins; disabled bindings fall through outward unless an
 *   evaluated entry sets `blocksOuterBindings`.
 *
 * The engine is deliberately free of Compose and AWT types so it can be unit-tested directly and reused by
 * both the standalone window resolver and the IJPL bridge veto.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class ShortcutDispatchEngine(
    private val keymap: () -> JewelKeymap,
    private val focusedBindings: () -> List<EngineBinding>,
    private val focusedClaims: () -> List<EngineClaim>,
) {
    private var pendingFirstStroke: JewelKeyStroke? = null
    private var suppressNextTyped = false

    public val isAwaitingSecondStroke: Boolean
        get() = pendingFirstStroke != null

    /** Call for KEY_TYPED-equivalent events; returns true when the event must be consumed. */
    public fun onTypedEvent(): Boolean {
        if (suppressNextTyped) {
            suppressNextTyped = false
            return true
        }
        return false
    }

    /** Clears state that must not survive focus changes or host disposal. */
    public fun reset() {
        pendingFirstStroke = null
        suppressNextTyped = false
    }

    /**
     * Call for every key-down. [stroke] must be null for modifier-only key-downs, which never match and
     * never cancel a pending chord.
     */
    public fun onKeyDown(stroke: JewelKeyStroke?): DispatchDecision {
        if (stroke == null) return DispatchDecision.Pass

        // A fresh key-down means the previously consumed press produced no typed event after all.
        suppressNextTyped = false

        // 1) Focused claims, innermost first.
        resolveClaim(stroke)?.let { claim ->
            claim.onInvoke()
            suppressNextTyped = true
            pendingFirstStroke = null
            return DispatchDecision.Consumed(
                invokedActionId = null,
                invokedSequence = claim.sequence,
                route = DispatchDecision.Consumed.Route.Claim,
            )
        }

        // 2) Pending two-stroke state: complete or cancel; either way the stroke is consumed.
        pendingFirstStroke?.let { first ->
            pendingFirstStroke = null
            suppressNextTyped = true
            val sequence = JewelKeySequence(first, stroke)
            val actionId =
                keymap().actionIdsFor(sequence).firstOrNull { id -> resolveBinding(id) != null }
            if (actionId != null) {
                resolveBinding(actionId)?.onInvoke?.invoke()
                return DispatchDecision.Consumed(actionId, sequence, DispatchDecision.Consumed.Route.Keymap)
            }
            return DispatchDecision.Consumed(null, null, DispatchDecision.Consumed.Route.ChordCancelled)
        }

        // 3) Exact one-stroke command wins over a chord sharing the first stroke.
        val exactSequence = JewelKeySequence(stroke)
        val exactActionId =
            keymap().actionIdsFor(exactSequence).firstOrNull { id -> resolveBinding(id) != null }
        if (exactActionId != null) {
            resolveBinding(exactActionId)?.onInvoke?.invoke()
            suppressNextTyped = true
            return DispatchDecision.Consumed(exactActionId, exactSequence, DispatchDecision.Consumed.Route.Keymap)
        }

        // 4) Chord prefix with at least one focused enabled target: consume and wait.
        val prefixTargets = keymap().actionIdsForPrefix(stroke)
        if (prefixTargets.any { id -> resolveBinding(id) != null }) {
            pendingFirstStroke = stroke
            suppressNextTyped = true
            return DispatchDecision.Consumed(null, null, DispatchDecision.Consumed.Route.ChordPrefix)
        }

        // 5) Unbound input stays ordinary input.
        return DispatchDecision.Pass
    }

    /** Innermost focused enabled binding; disabled entries fall through outward unless they block. */
    private fun resolveBinding(actionId: JewelActionId): EngineBinding? {
        for (binding in focusedBindings().asReversed()) {
            if (binding.actionId != actionId) continue
            if (binding.enabled) return binding
            if (binding.blocksOuterBindings) return null
        }
        return null
    }

    private fun resolveClaim(stroke: JewelKeyStroke): EngineClaim? {
        for (claim in focusedClaims().asReversed()) {
            val matchesFirst = claim.sequence.first == stroke && claim.sequence.second == null
            if (matchesFirst && claim.enabled) return claim
            if (claim.sequence.first == stroke && !claim.enabled && claim.blocksOuterClaims) return null
        }
        return null
    }
}
