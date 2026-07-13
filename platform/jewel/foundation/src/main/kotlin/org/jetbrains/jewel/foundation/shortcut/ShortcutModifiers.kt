// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.platform.InspectorInfo
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.InternalJewelApi

/**
 * Binds a previously registered action ID to this UI node. The binding participates in dispatch only while
 * this node's subtree has focus; the nearest focused enabled binding for an action wins, disabled bindings
 * fall through outward, and `blocksOuterBindings = true` stops that fall-through.
 *
 * Like `Modifier.provideData`, the node observes focus of nodes attached AFTER it in the modifier chain
 * (and of descendants), so it must come before `focusable()` in the chain.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public fun Modifier.shortcut(
    action: JewelAction,
    enabled: Boolean = true,
    blocksOuterBindings: Boolean = false,
    onInvoke: () -> Unit,
): Modifier = this then ShortcutBindingElement(action, enabled, blocksOuterBindings, onInvoke)

/**
 * Claims a one-stroke physical shortcut before host keymap lookup while this node's subtree has focus. The
 * deliberate, review-visible escape hatch for editor-like components; in the IJPL bridge it also makes the
 * host skip `IdeKeyEventDispatcher` for the claimed stroke.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public fun Modifier.claimShortcut(
    sequence: JewelKeySequence,
    enabled: Boolean = true,
    blocksOuterClaims: Boolean = false,
    onInvoke: () -> Unit,
): Modifier = this then ShortcutClaimElement(sequence, enabled, blocksOuterClaims, onInvoke)

/**
 * Low-level single-event ownership for focused input that is not a shortcut sequence. A matching enabled
 * claim owns and consumes the event; it never falls through to the host keymap.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public fun Modifier.claimKeyEvent(
    matcher: (KeyEvent) -> Boolean,
    enabled: Boolean = true,
    onKeyEvent: (KeyEvent) -> Unit,
): Modifier = this then RawKeyClaimElement(matcher, enabled, onKeyEvent)

@InternalJewelApi
@ApiStatus.Internal
public class ShortcutBindingNode(
    public var action: JewelAction,
    public var enabled: Boolean,
    public var blocksOuterBindings: Boolean,
    public var onInvoke: () -> Unit,
) : Modifier.Node(), FocusEventModifierNode, TraversableNode {
    public var hasFocus: Boolean = false
        private set

    override fun onFocusEvent(focusState: FocusState) {
        hasFocus = focusState.hasFocus
    }

    override val traverseKey: TraverseKey = TraverseKey

    public companion object TraverseKey
}

private class ShortcutBindingElement(
    private val action: JewelAction,
    private val enabled: Boolean,
    private val blocksOuterBindings: Boolean,
    private val onInvoke: () -> Unit,
) : ModifierNodeElement<ShortcutBindingNode>() {
    override fun create() = ShortcutBindingNode(action, enabled, blocksOuterBindings, onInvoke)

    override fun update(node: ShortcutBindingNode) {
        node.action = action
        node.enabled = enabled
        node.blocksOuterBindings = blocksOuterBindings
        node.onInvoke = onInvoke
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "shortcut"
        properties["action"] = action.id.value
        properties["enabled"] = enabled
    }

    override fun equals(other: Any?): Boolean =
        other is ShortcutBindingElement &&
            other.action == action &&
            other.enabled == enabled &&
            other.blocksOuterBindings == blocksOuterBindings &&
            other.onInvoke === onInvoke

    override fun hashCode(): Int = 31 * action.hashCode() + enabled.hashCode()
}

@InternalJewelApi
@ApiStatus.Internal
public class ShortcutClaimNode(
    public var sequence: JewelKeySequence,
    public var enabled: Boolean,
    public var blocksOuterClaims: Boolean,
    public var onInvoke: () -> Unit,
) : Modifier.Node(), FocusEventModifierNode, TraversableNode {
    public var hasFocus: Boolean = false
        private set

    override fun onFocusEvent(focusState: FocusState) {
        hasFocus = focusState.hasFocus
    }

    override val traverseKey: TraverseKey = TraverseKey

    public companion object TraverseKey
}

private class ShortcutClaimElement(
    private val sequence: JewelKeySequence,
    private val enabled: Boolean,
    private val blocksOuterClaims: Boolean,
    private val onInvoke: () -> Unit,
) : ModifierNodeElement<ShortcutClaimNode>() {
    override fun create() = ShortcutClaimNode(sequence, enabled, blocksOuterClaims, onInvoke)

    override fun update(node: ShortcutClaimNode) {
        node.sequence = sequence
        node.enabled = enabled
        node.blocksOuterClaims = blocksOuterClaims
        node.onInvoke = onInvoke
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "claimShortcut"
        properties["sequence"] = sequence.displayText()
        properties["enabled"] = enabled
    }

    override fun equals(other: Any?): Boolean =
        other is ShortcutClaimElement &&
            other.sequence == sequence &&
            other.enabled == enabled &&
            other.blocksOuterClaims == blocksOuterClaims &&
            other.onInvoke === onInvoke

    override fun hashCode(): Int = 31 * sequence.hashCode() + enabled.hashCode()
}

@InternalJewelApi
@ApiStatus.Internal
public class RawKeyClaimNode(
    public var matcher: (KeyEvent) -> Boolean,
    public var enabled: Boolean,
    public var onKeyEvent: (KeyEvent) -> Unit,
) : Modifier.Node(), FocusEventModifierNode, TraversableNode {
    public var hasFocus: Boolean = false
        private set

    override fun onFocusEvent(focusState: FocusState) {
        hasFocus = focusState.hasFocus
    }

    override val traverseKey: TraverseKey = TraverseKey

    public companion object TraverseKey
}

private class RawKeyClaimElement(
    private val matcher: (KeyEvent) -> Boolean,
    private val enabled: Boolean,
    private val onKeyEvent: (KeyEvent) -> Unit,
) : ModifierNodeElement<RawKeyClaimNode>() {
    override fun create() = RawKeyClaimNode(matcher, enabled, onKeyEvent)

    override fun update(node: RawKeyClaimNode) {
        node.matcher = matcher
        node.enabled = enabled
        node.onKeyEvent = onKeyEvent
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "claimKeyEvent"
        properties["enabled"] = enabled
    }

    override fun equals(other: Any?): Boolean =
        other is RawKeyClaimElement &&
            other.matcher === matcher &&
            other.enabled == enabled &&
            other.onKeyEvent === onKeyEvent

    override fun hashCode(): Int = 31 * matcher.hashCode() + enabled.hashCode()
}
