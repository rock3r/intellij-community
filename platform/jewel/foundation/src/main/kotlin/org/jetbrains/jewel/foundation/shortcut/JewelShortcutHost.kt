// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.traverseDescendants
import androidx.compose.ui.platform.InspectorInfo
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.InternalJewelApi

/**
 * One shortcut-dispatch host per Compose surface (a standalone window, or a bridge panel).
 *
 * Install [resolverRootModifier] at the surface's content root, then:
 * - standalone: pass [onPreviewKeyEvent] to the `Window(onPreviewKeyEvent = …)` parameter. That hook runs
 *   before scene dispatch and sees KEY_TYPED events, which is required for typed suppression — a root
 *   `Modifier.onPreviewKeyEvent` sees no typed events and cannot prevent a claimed printable key from
 *   inserting its character.
 * - IJPL bridge: the panel wrapper consults [claimsKeyDown] from `KeyboardAwareFocusOwnerProvider` to skip
 *   `IdeKeyEventDispatcher` for claimed strokes; commands stay with the IJPL keymap. Compose popups/dialogs
 *   run in their own scene layers with separate key handling: Jewel-owned popups must thread
 *   [onPreviewKeyEvent] into their `Popup(onPreviewKeyEvent = …)` for dispatch to work while they are open.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class JewelShortcutHostState(private val keymapProvider: () -> JewelKeymap) {
    private var rootNode: ShortcutResolverRootNode? = null

    private val engine =
        ShortcutDispatchEngine(
            keymap = keymapProvider,
            focusedBindings = ::collectFocusedBindings,
            focusedClaims = ::collectFocusedClaims,
        )

    /** Fired after every consumed dispatch; drives Presentation Assistant-style overlays and tests. */
    public var onDispatch: ((DispatchDecision.Consumed) -> Unit)? = null

    public val isAwaitingSecondStroke: Boolean
        get() = engine.isAwaitingSecondStroke

    public val resolverRootModifier: Modifier
        get() = Modifier then ShortcutResolverRootElement(this)

    /** Window-level AWT pre-scene handler; consumes claimed/mapped strokes and suppressed typed events. */
    public fun onPreviewKeyEvent(event: KeyEvent): Boolean {
        when (event.type) {
            KeyEventType.KeyDown -> {
                val decision = engine.onKeyDown(JewelKeyStroke.fromKeyDownOrNull(event))
                if (decision is DispatchDecision.Consumed) {
                    onDispatch?.invoke(decision)
                    return true
                }
                // Raw event claims: deliberately local, innermost enabled record wins.
                resolveRawClaim(event)?.let { claim ->
                    claim.onKeyEvent(event)
                    return true
                }
                return false
            }
            KeyEventType.KeyUp -> return false
            // KEY_TYPED and other unknown AWT events surface as Unknown at this hook.
            else -> return engine.onTypedEvent()
        }
    }

    /**
     * The IJPL bridge veto: true when a focused claim owns this key-down. Only claims veto the IDE keymap;
     * commands remain IJPL actions resolved through the platform keymap.
     */
    public fun claimsKeyDown(event: KeyEvent): Boolean {
        val stroke = JewelKeyStroke.fromKeyDownOrNull(event) ?: return false
        val claimed =
            collectFocusedClaims().asReversed().firstOrNull { it.sequence.first == stroke && it.enabled } != null
        return claimed || resolveRawClaim(event) != null
    }

    /** Clears chord/typed state; call on focus loss or host disposal. */
    public fun reset() {
        engine.reset()
    }

    internal fun attachRoot(node: ShortcutResolverRootNode) {
        rootNode = node
    }

    internal fun detachRoot(node: ShortcutResolverRootNode) {
        if (rootNode === node) rootNode = null
        engine.reset()
    }

    private fun collectFocusedBindings(): List<EngineBinding> {
        val result = mutableListOf<EngineBinding>()
        // Pre-order traversal visits ancestors before descendants, so focused nodes accumulate
        // outermost-first and the engine treats the LAST entries as innermost.
        rootNode?.traverseDescendants(ShortcutBindingNode.TraverseKey) { node ->
            if (node is ShortcutBindingNode && node.hasFocus) {
                result.add(
                    EngineBinding(
                        actionId = node.action.id,
                        enabled = node.enabled,
                        blocksOuterBindings = node.blocksOuterBindings,
                        origin = node.action.title,
                        onInvoke = node.onInvoke,
                    )
                )
            }
            TraversableNode.Companion.TraverseDescendantsAction.ContinueTraversal
        }
        return result
    }

    private fun collectFocusedClaims(): List<EngineClaim> {
        val result = mutableListOf<EngineClaim>()
        rootNode?.traverseDescendants(ShortcutClaimNode.TraverseKey) { node ->
            if (node is ShortcutClaimNode && node.hasFocus) {
                result.add(
                    EngineClaim(
                        sequence = node.sequence,
                        enabled = node.enabled,
                        blocksOuterClaims = node.blocksOuterClaims,
                        onInvoke = node.onInvoke,
                    )
                )
            }
            TraversableNode.Companion.TraverseDescendantsAction.ContinueTraversal
        }
        return result
    }

    private fun resolveRawClaim(event: KeyEvent): RawKeyClaimNode? {
        var match: RawKeyClaimNode? = null
        rootNode?.traverseDescendants(RawKeyClaimNode.TraverseKey) { node ->
            if (node is RawKeyClaimNode && node.hasFocus && node.enabled && node.matcher(event)) {
                // Keep overwriting: the innermost focused match is visited last in pre-order.
                match = node
            }
            TraversableNode.Companion.TraverseDescendantsAction.ContinueTraversal
        }
        return match
    }
}

/** Remembers a [JewelShortcutHostState] for [keymap]; the state survives keymap switches via the lambda. */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun rememberJewelShortcutHostState(keymap: () -> JewelKeymap): JewelShortcutHostState =
    remember { JewelShortcutHostState(keymap) }

@InternalJewelApi
@ApiStatus.Internal
public class ShortcutResolverRootNode(public var state: JewelShortcutHostState) :
    Modifier.Node(), TraversableNode {
    override val traverseKey: TraverseKey = TraverseKey

    override fun onAttach() {
        state.attachRoot(this)
    }

    override fun onDetach() {
        state.detachRoot(this)
    }

    public companion object TraverseKey
}

private class ShortcutResolverRootElement(private val state: JewelShortcutHostState) :
    ModifierNodeElement<ShortcutResolverRootNode>() {
    override fun create() = ShortcutResolverRootNode(state)

    override fun update(node: ShortcutResolverRootNode) {
        node.state.detachRoot(node)
        node.state = state
        state.attachRoot(node)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "jewelShortcutResolverRoot"
    }

    override fun equals(other: Any?): Boolean = other is ShortcutResolverRootElement && other.state === state

    override fun hashCode(): Int = state.hashCode()
}
