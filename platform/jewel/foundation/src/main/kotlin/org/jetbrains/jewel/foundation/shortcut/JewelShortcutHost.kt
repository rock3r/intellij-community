// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.traverseDescendants
import androidx.compose.ui.platform.InspectorInfo
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.InternalJewelApi
import org.jetbrains.jewel.foundation.util.myLogger

/**
 * One shortcut-dispatch host per Compose surface (a standalone window, or a bridge panel).
 *
 * Install [resolverRootModifier] at the surface's content root, then:
 * - standalone: pass [onPreviewKeyEvent] to the `Window(onPreviewKeyEvent = …)` parameter. That hook runs before scene
 *   dispatch and sees KEY_TYPED events, which is required for typed suppression — a root `Modifier.onPreviewKeyEvent`
 *   sees no typed events and cannot prevent a claimed printable key from inserting its character.
 * - IJPL bridge: the panel wrapper consults [claimsKeyDown] from `KeyboardAwareFocusOwnerProvider` to skip
 *   `IdeKeyEventDispatcher` for claimed strokes; commands stay with the IJPL keymap. Compose popups/dialogs run in
 *   their own scene layers with separate key handling: Jewel-owned popups must thread [onPreviewKeyEvent] into their
 *   `Popup(onPreviewKeyEvent = …)` for dispatch to work while they are open.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class JewelShortcutHostState(
    private val registry: JewelActionRegistry? = null,
    private val keymapProvider: () -> JewelKeymap,
) {
    private var rootNode: ShortcutResolverRootNode? = null

    private val logger = myLogger()

    /** Action IDs already reported as unregistered; presentation polls must not spam the log. */
    private val reportedUnregistered = ConcurrentHashMap.newKeySet<JewelActionId>()

    private val engine =
        ShortcutDispatchEngine(
            keymap = keymapProvider,
            focusedBindings = ::collectFocusedBindings,
            focusedClaims = ::collectFocusedClaims,
        )

    /** Fired after every consumed dispatch; drives Presentation Assistant-style overlays and tests. */
    public var onDispatch: ((DispatchDecision.Consumed) -> Unit)? = null

    private val eventSource = MutableActionEventSource()

    /** One event per completed Jewel-owned invocation (keyboard and [invoker]). */
    public val events: ActionEventSource
        get() = eventSource

    /**
     * Demand-driven presentation sampling for this host. Poll on your own signals via
     * [ActionPresentationScheduler.invalidate]; keyboard dispatches invalidate automatically.
     */
    public val presentations: ActionPresentationScheduler = ActionPresentationScheduler(::samplePresentation)

    /**
     * Normal host action execution. The standalone default resolves the nearest focused enabled Jewel binding and emits
     * through [events]; the IJPL bridge substitutes a platform-routing invoker so `ActionManager` update, enablement,
     * and listeners stay authoritative there.
     */
    public var invoker: ActionInvoker =
        object : ActionInvoker {
            override fun invoke(action: JewelAction, trigger: ActionTrigger): ActionDispatchResult {
                val binding =
                    engine.resolveFocusedBinding(action.id)
                        ?: return ActionDispatchResult.Rejected(ActionDispatchRejection.NoFocusedBinding)
                runResolvedInvocation(action.id, trigger, binding.onInvoke)
                return ActionDispatchResult.Dispatched
            }
        }

    /**
     * Runs [handler] as one completed Jewel-owned invocation of [actionId]: exactly one [events] emission, then a
     * presentation re-sample. This is the emission point for host integrations that resolve focused handlers themselves
     * (the IJPL bridge action); keyboard dispatch through [onPreviewKeyEvent] emits on its own and must not be routed
     * through here too.
     */
    public fun runResolvedInvocation(actionId: JewelActionId, trigger: ActionTrigger, handler: () -> Unit) {
        handler()
        eventSource.emit(ActionInvocation(actionId, (trigger as? ActionTrigger.Keyboard)?.sequence, trigger))
        presentations.invalidate()
    }

    public val isAwaitingSecondStroke: Boolean
        get() = engine.isAwaitingSecondStroke

    /** Innermost focused enabled handler for [actionId]; null when none (action disabled here). */
    public fun resolveFocusedHandler(actionId: JewelActionId): (() -> Unit)? =
        engine.resolveFocusedBinding(actionId)?.onInvoke

    /** The active keymap's shortcuts for [actionId]; empty in hosts whose keymap lives elsewhere (bridge). */
    public fun shortcutsFor(actionId: JewelActionId): List<JewelKeySequence> = keymapProvider().shortcutsFor(actionId)

    /**
     * The action's current presentation for this host: the failure rows of the PRD table (Unregistered when a
     * [registry] is installed and does not know the ID; NoFocusedBinding otherwise) or the nearest focused enabled
     * binding's [ActionPresentationOverride] merged over the action's template.
     */
    public fun presentationFor(actionId: JewelActionId): ActionPresentation = samplePresentation(actionId)

    private fun samplePresentation(actionId: JewelActionId): ActionPresentation {
        val binding = engine.resolveFocusedBinding(actionId)
        if (binding != null) {
            return binding.presentationOverride.mergeOver(
                ActionPresentation(text = binding.origin, enabled = true, resolution = ActionResolution.Resolved)
            )
        }
        val definition = registry?.definition(actionId)
        if (registry != null && definition == null) {
            if (reportedUnregistered.add(actionId)) {
                logger.warn(
                    "Action '${actionId.value}' is not registered with this host's action registry; " +
                        "controls bound to it render disabled. Further reports for it are suppressed."
                )
            }
            return ActionPresentation(
                text = actionId.value,
                enabled = false,
                resolution = ActionResolution.Unregistered,
            )
        }
        return ActionPresentation(
            text = definition?.action?.title ?: actionId.value,
            enabled = false,
            resolution = ActionResolution.NoFocusedBinding,
        )
    }

    public val resolverRootModifier: Modifier
        get() = Modifier then ShortcutResolverRootElement(this)

    private val menuScopes = ArrayDeque<MenuShortcutScope>()

    /**
     * Opens a menu-local shortcut scope for a menu that just became visible. While at least one scope is open, the
     * innermost (most recently opened) scope's strokes resolve *before* ordinary dispatch, and matched strokes are
     * consumed with typed suppression — the single dispatcher for open menus, absorbing what menu-local key handling
     * used to do so the two can never race. Close the scope when the menu closes; scopes must be closed in reverse
     * opening order (innermost first).
     */
    public fun openMenuShortcutScope(): MenuShortcutScope {
        val scope = MenuShortcutScope(this)
        menuScopes.addLast(scope)
        return scope
    }

    internal fun closeMenuScope(scope: MenuShortcutScope) {
        menuScopes.remove(scope)
    }

    private fun resolveMenuScopeAction(stroke: JewelKeyStroke?): (() -> Unit)? {
        if (stroke == null) return null
        return menuScopes.lastOrNull()?.actionFor(stroke)
    }

    /** Window-level AWT pre-scene handler; consumes claimed/mapped strokes and suppressed typed events. */
    public fun onPreviewKeyEvent(event: KeyEvent): Boolean {
        when (event.type) {
            KeyEventType.KeyDown -> {
                resolveMenuScopeAction(JewelKeyStroke.fromKeyDownOrNull(event))?.let { action ->
                    engine.armTypedSuppression()
                    action()
                    presentations.invalidate()
                    return true
                }
                val decision = engine.onKeyDown(JewelKeyStroke.fromKeyDownOrNull(event))
                if (decision is DispatchDecision.Consumed) {
                    onDispatch?.invoke(decision)
                    if (
                        decision.route == DispatchDecision.Consumed.Route.Claim ||
                            decision.route == DispatchDecision.Consumed.Route.Keymap
                    ) {
                        eventSource.emit(
                            ActionInvocation(
                                decision.invokedActionId,
                                decision.invokedSequence,
                                ActionTrigger.Keyboard(decision.invokedSequence),
                            )
                        )
                        presentations.invalidate()
                    }
                    return true
                }
                // Raw event claims: deliberately local, innermost enabled record wins.
                resolveRawClaim(event)?.let { claim ->
                    claim.onKeyEvent(event)
                    return true
                }
                return false
            }
            KeyEventType.KeyUp -> {
                engine.onKeyUp(JewelKeyStroke.fromKeyDownOrNull(event))
                return false
            }
            // KEY_TYPED and other unknown AWT events surface as Unknown at this hook.
            else -> return engine.onTypedEvent()
        }
    }

    /**
     * The IJPL bridge veto: true when a focused claim owns this key-down. Only claims veto the IDE keymap; commands
     * remain IJPL actions resolved through the platform keymap.
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
                        repeatPolicy = node.repeatPolicy,
                        presentationOverride = node.presentationOverride,
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
                        repeatPolicy = node.repeatPolicy,
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
            if (node is RawKeyClaimNode && node.claims(event)) {
                // Keep overwriting: the innermost focused match is visited last in pre-order.
                match = node
            }
            TraversableNode.Companion.TraverseDescendantsAction.ContinueTraversal
        }
        return match
    }

    private fun RawKeyClaimNode.claims(event: KeyEvent): Boolean = hasFocus && enabled && matcher(event)
}

/**
 * Menu-local shortcuts for one open menu, resolved by the owning [JewelShortcutHostState] ahead of ordinary dispatch
 * while this is the innermost open scope. Registrations are replaced wholesale on menu content changes ([replaceAll]);
 * [close] must be called when the menu closes.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class MenuShortcutScope internal constructor(private val host: JewelShortcutHostState) : AutoCloseable {
    private val entries = LinkedHashMap<JewelKeyStroke, () -> Unit>()

    public fun register(stroke: JewelKeyStroke, onInvoke: () -> Unit) {
        entries[stroke] = onInvoke
    }

    public fun replaceAll(newEntries: Map<JewelKeyStroke, () -> Unit>) {
        entries.clear()
        entries.putAll(newEntries)
    }

    internal fun actionFor(stroke: JewelKeyStroke): (() -> Unit)? = entries[stroke]

    override fun close() {
        entries.clear()
        host.closeMenuScope(this)
    }
}

/** Remembers a [JewelShortcutHostState] for [keymap]; the state survives keymap switches via the lambda. */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun rememberJewelShortcutHostState(
    registry: JewelActionRegistry? = null,
    keymap: () -> JewelKeymap,
): JewelShortcutHostState = remember { JewelShortcutHostState(registry, keymap) }

@InternalJewelApi
@ApiStatus.Internal
public class ShortcutResolverRootNode(public var state: JewelShortcutHostState) :
    Modifier.Node(), TraversableNode, KeyInputModifierNode {
    override val traverseKey: TraverseKey = TraverseKey

    override fun onAttach() {
        state.attachRoot(this)
    }

    override fun onDetach() {
        state.detachRoot(this)
    }

    /**
     * Scene-level dispatch, on the preview (tunneling) pass so focused claims keep beating ordinary component input.
     * This is what makes shortcuts observable to plain Compose UI tests: injected key events (`performKeyInput`) reach
     * the host with no window or AWT hook present.
     *
     * Safe next to the production hooks: anything the window hook or the bridge's key-event dispatcher consumes never
     * reaches the scene, and an event they passed re-evaluates to the same pass here without mutating dispatch state.
     * KEY_TYPED suppression stays with the AWT-level hooks — scenes never see typed events.
     */
    override fun onPreKeyEvent(event: KeyEvent): Boolean = state.onPreviewKeyEvent(event)

    override fun onKeyEvent(event: KeyEvent): Boolean = false

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
