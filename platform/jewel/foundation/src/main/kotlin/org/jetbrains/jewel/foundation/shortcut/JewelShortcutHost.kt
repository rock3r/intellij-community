// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
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
import org.jetbrains.jewel.foundation.JewelFlags
import org.jetbrains.jewel.foundation.actionSystem.DataProviderContext
import org.jetbrains.jewel.foundation.actionSystem.DataProviderNode
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
 *
 * **Threading:** dispatch is UI-thread-synchronous — [onPreviewKeyEvent], [claimsKeyDown], and [runResolvedInvocation]
 * must run on the surface's UI thread (the AWT event dispatch thread in production), and the bound handlers they invoke
 * run there too. Violations are logged as errors, or thrown when [org.jetbrains.jewel.foundation.JewelFlags.strictMode]
 * is enabled. Presentation sampling is different: [presentationFor] and [presentations] read an atomically published
 * snapshot of the focused bindings that may be up to one UI frame stale. That snapshot read is thread-safe, but whether
 * sampling as a whole is depends on the backing: with the IJPL bridge backing it is safe from any thread (the bridge
 * samples from background action updates and reads the platform data context); with the standalone default backing,
 * resolving a binding that computes its presentation from the focused Compose tree must run on the surface's UI thread.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class JewelShortcutHostState(
    private val registry: JewelActionRegistry? = null,
    private val keymapProvider: () -> JewelKeymap,
) {
    @Volatile private var rootNode: ShortcutResolverRootNode? = null

    private val logger = myLogger()

    /** Action IDs already reported as unregistered; presentation polls must not spam the log. */
    private val reportedUnregistered = ConcurrentHashMap.newKeySet<JewelActionId>()

    private val engine =
        ShortcutDispatchEngine(
            keymap = keymapProvider,
            focusedBindings = { rootNode?.engineBindings() ?: emptyList() },
            focusedClaims = { rootNode?.engineClaims() ?: emptyList() },
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
     *
     * This is the sampling path the IJPL bridge rides (its `TimerListener` calls
     * [ActionPresentationScheduler.invalidate] on the platform's action-update cadence). Standalone hosts default to
     * [reactivePresentation] instead — a Compose-snapshot-reactive derivation with no polling — and use this only
     * through the documented [invalidate] escape hatch for genuinely non-snapshot data sources.
     */
    public val presentations: ActionPresentationScheduler = ActionPresentationScheduler(::samplePresentation)

    /**
     * Whether action-bound controls derive their presentation reactively from Compose snapshot state (the standalone
     * default) or observe the demand-driven [presentations] scheduler (the IJPL bridge).
     *
     * Standalone, presentation is a `derivedStateOf` over the focused bindings and the live `Modifier.provideData`
     * values, so a control recomputes exactly when the snapshot state its `update` block reads changes — no manual
     * invalidation, and only the affected controls recompute. The IJPL bridge sets this `false`: it wraps the platform
     * `DataContext`, which cannot be observed as snapshot state, so it keeps sampling on the platform's update cadence.
     */
    @Suppress("MemberVisibilityCanBePrivate") public var reactivePresentation: Boolean = true

    /**
     * A snapshot-state generation counter the reactive presentation derivation reads to depend on structural changes it
     * cannot otherwise observe: the focused binding set (registrations on focus), a binding's static parameters, and
     * focus moving between provider subtrees. Datum changes are observed directly through the live context, so they do
     * NOT bump this — that is what keeps a hot provided datum from recomputing unrelated controls. Bumped on the UI
     * thread; ignored entirely when [reactivePresentation] is off (the bridge never reads it).
     */
    private val presentationRevision = mutableIntStateOf(0)

    init {
        // Every place that already invalidates the demand-driven scheduler (registration, binding-parameter changes,
        // dispatch) is exactly where the reactive derivation's structural dependencies change too, so route the
        // revision
        // bump through the same signal instead of duplicating call sites. The bridge's off-thread invalidations are
        // gated out inside the bump.
        presentations.onInvalidated = ::bumpPresentationRevision
    }

    /**
     * Bumps [presentationRevision] on the reactive path; a no-op for the bridge, keeping its off-thread invalidations
     * clear of snapshot state.
     */
    internal fun bumpPresentationRevision() {
        if (!reactivePresentation) return
        presentationRevision.intValue++
    }

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
        checkUiThread("runResolvedInvocation")
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
     * Produces the [ActionContext] actions read for enablement and dynamic content. The standalone default collects the
     * focused surface's `Modifier.provideData` values (the same providers the IJPL bridge sinks into the platform data
     * context); the bridge replaces this to wrap the platform `DataContext` directly, so the whole IDE's data —
     * project, editor, selection — is visible with the platform's nearest-provider-wins precedence.
     *
     * Reassign to change the backing (the bridge does, once per panel). Evaluated on demand: keep it cheap.
     */
    public var contextProvider: () -> ActionContext = { rootNode?.collectActionContext() ?: ActionContext.Empty }

    /**
     * The current [ActionContext] for this host — a stable-for-the-moment snapshot of the data actions resolve against.
     *
     * **Threading:** the standalone backing walks the focused Compose tree, so call it on the surface's UI thread; the
     * bridge backing is safe wherever the platform allows its data context to be read.
     */
    public fun currentActionContext(): ActionContext = contextProvider()

    /**
     * The action's current presentation for this host: the failure states (Unregistered when a [registry] is installed
     * and does not know the ID; NoFocusedBinding otherwise) or the nearest focused enabled binding's
     * [ActionPresentationOverride] merged over the action's template.
     *
     * **Threading:** reads an atomically published snapshot of the focused bindings; the result may be up to one UI
     * frame stale, which the next demand-driven sample corrects. Safe to call from any thread only with the IJPL bridge
     * backing, which samples from background action updates and reads the platform data context. With the standalone
     * default backing, resolving a binding that computes its presentation from the focused Compose tree must run on the
     * surface's UI thread. Presentation is advisory: dispatch itself never consults it.
     */
    public fun presentationFor(actionId: JewelActionId): ActionPresentation = samplePresentation(actionId)

    private fun samplePresentation(actionId: JewelActionId): ActionPresentation {
        // The scheduler path resolves through the engine, whose bindings evaluate their update block against the
        // materialized context; the reactive path (reactivePresentationOf) resolves the same bindings against the live
        // context. Both then hand off to the shared presentation builders so the two can never diverge on how a
        // resolved override or a failure row is rendered.
        val binding = engine.resolveFocusedBinding(actionId)
        return if (binding != null) {
            resolvedPresentation(actionId, binding.origin, binding.presentationOverride)
        } else {
            unresolvedPresentation(actionId)
        }
    }

    /**
     * The Compose-snapshot-reactive presentation for [actionId], the standalone counterpart of [samplePresentation].
     * Evaluated inside a `derivedStateOf` (see [collectPresentationAsState]); its reads decide the recompute set:
     * - [presentationRevision] establishes a dependency on the focused binding set and each binding's static
     *   parameters, so a registration or focus change recomputes;
     * - the [LiveActionContext] each candidate binding's `update` block reads reaches the snapshot state behind the
     *   focused `Modifier.provideData` values per key, so only the controls whose read data changed recompute.
     *
     * It resolves only bindings for [actionId], innermost-first, evaluating each `update` block against the live
     * context until one is enabled — the same nearest-enabled-wins / fall-through-outward contract
     * [ShortcutDispatchEngine] uses, but evaluated where the derivation can track the reads.
     */
    internal fun reactivePresentationOf(actionId: JewelActionId): ActionPresentation {
        // Read the revision first so the derivation depends on structural changes even before touching any binding.
        @Suppress("UNUSED_EXPRESSION") presentationRevision.intValue
        val root = rootNode
        if (root != null) {
            val liveContext = LiveActionContext(root)
            // Innermost bindings come LAST in the snapshot, so reversed() is nearest-first.
            for (node in root.bindingNodesSnapshot().asReversed()) {
                if (node.action.id != actionId) continue
                val evaluated = evaluateBindingUpdate(node, liveContext)
                if (evaluated.enabled) {
                    return resolvedPresentation(actionId, node.action.title, evaluated.override)
                }
                // A disabled binding falls through outward unless it blocks; a blocking one ends the search here.
                if (node.blocksOuterBindings) break
            }
        }
        return unresolvedPresentation(actionId)
    }

    /**
     * Builds the presentation for a resolved binding: the action's live/template base with the binding's effective
     * [override] merged on top. Shared by both the scheduler and reactive paths.
     */
    private fun resolvedPresentation(
        actionId: JewelActionId,
        origin: String,
        override: ActionPresentationOverride,
    ): ActionPresentation {
        // The template is the shared base, so the icon, description and authored flags survive regardless of how the
        // action resolved; a host-computed sample (the platform's update() in the bridge) replaces it when present.
        val template = registry?.definition(actionId)?.action?.template
        val live = registry?.sampledPresentation(actionId)
        val base =
            live?.copy(enabled = true, resolution = ActionResolution.Resolved)
                ?: template?.sampled(enabled = true, resolution = ActionResolution.Resolved)
                // No registry: the binding itself is the only source of authored text.
                ?: ActionPresentation(text = origin, enabled = true, resolution = ActionResolution.Resolved)
        return override.mergeOver(base)
    }

    /** Builds the failure/unfocused presentation (Unregistered or NoFocusedBinding). Shared by both paths. */
    private fun unresolvedPresentation(actionId: JewelActionId): ActionPresentation {
        val definition = registry?.definition(actionId)
        val live = registry?.sampledPresentation(actionId)
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
        return live?.copy(resolution = ActionResolution.NoFocusedBinding)
            ?: definition?.action?.template?.sampled(enabled = false, resolution = ActionResolution.NoFocusedBinding)
            ?: ActionPresentation(
                text = actionId.value,
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
        checkUiThread("onPreviewKeyEvent")
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
        checkUiThread("claimsKeyDown")
        val stroke = JewelKeyStroke.fromKeyDownOrNull(event)
        // Delegates to the engine's claim resolution so veto and delivery can never disagree: a disabled inner
        // claim with blocksOuterClaims suppresses both, and only one-stroke claims veto.
        return (stroke != null && engine.claimsStroke(stroke)) || resolveRawClaim(event) != null
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

    private fun resolveRawClaim(event: KeyEvent): RawKeyClaimNode? = rootNode?.rawClaimFor(event)

    /**
     * Dispatch entry points and the handlers they invoke are bound to the surface's UI thread — the thread the resolver
     * root attached on (the AWT EDT in production, the test thread under a Compose test rule). Violations log an error,
     * or throw when [JewelFlags.strictMode] is on. No root attached means no surface, so no contract to enforce (plain
     * unit tests drive the engine directly).
     */
    private fun checkUiThread(entryPoint: String) {
        val expected = rootNode?.uiThread ?: return
        val actual = Thread.currentThread()
        if (actual !== expected) {
            val message =
                "$entryPoint must run on the surface's UI thread ('${expected.name}') but was called on " +
                    "'${actual.name}'. Shortcut dispatch is UI-thread-synchronous; only presentation sampling " +
                    "(presentationFor) is safe from other threads."
            if (JewelFlags.strictMode) error(message) else logger.error(message)
        }
    }
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
    Modifier.Node(), TraversableNode, KeyInputModifierNode, FocusEventModifierNode {
    override val traverseKey: TraverseKey = TraverseKey

    private var subtreeHadFocus = false

    /**
     * Focus-driven registrations replace per-keystroke subtree traversal: participant nodes register on focus-gain and
     * deregister on focus-loss/detach (see [ShortcutRegistrarNode]). The registry lives on this node — not on the host
     * state — because [ShortcutResolverRootElement.update] can swap the state under a stable root without any focus
     * transitions firing; node ownership keeps registrations valid across such swaps. Mutations happen on the UI
     * thread; each mutation publishes an immutable, outermost-first-sorted snapshot so presentation sampling can read
     * from any thread (at most one UI frame stale).
     */
    private val focusedBindingNodes = mutableListOf<ShortcutBindingNode>()
    private val focusedClaimNodes = mutableListOf<ShortcutClaimNode>()
    private val focusedRawClaimNodes = mutableListOf<RawKeyClaimNode>()

    @Volatile private var bindingSnapshot: List<ShortcutBindingNode> = emptyList()

    @Volatile private var claimSnapshot: List<ShortcutClaimNode> = emptyList()

    @Volatile private var rawClaimSnapshot: List<RawKeyClaimNode> = emptyList()

    /** The surface's UI thread, recorded at attach; dispatch entry points are bound to it. */
    internal var uiThread: Thread? = null
        private set

    internal fun register(node: ShortcutRegistrarNode) {
        registerNode(node)
        // The focused binding set just changed, so every sampled presentation is stale: a control bound to an
        // action that just became (un)available must re-render. Keyboard dispatch invalidates on its own, and the
        // IJPL bridge additionally rides the platform's action-update cadence, but a focus change alone has no
        // other signal — without this, standalone action components render whatever was true at first sample.
        state.presentations.invalidate()
    }

    private fun registerNode(node: ShortcutRegistrarNode) {
        when (node) {
            is ShortcutBindingNode -> {
                focusedBindingNodes.add(node)
                bindingSnapshot = sortedSnapshot(focusedBindingNodes)
            }
            is ShortcutClaimNode -> {
                focusedClaimNodes.add(node)
                claimSnapshot = sortedSnapshot(focusedClaimNodes)
            }
            is RawKeyClaimNode -> {
                focusedRawClaimNodes.add(node)
                rawClaimSnapshot = sortedSnapshot(focusedRawClaimNodes)
            }
        }
    }

    internal fun deregister(node: ShortcutRegistrarNode) {
        deregisterNode(node)
        state.presentations.invalidate()
    }

    private fun deregisterNode(node: ShortcutRegistrarNode) {
        // Re-sort on removal exactly as [registerNode] does: the backing lists are only ever appended to, so they
        // stay in registration (insertion) order, which is NOT depth order. A plain copy here would republish the
        // snapshot in insertion order and make the engine treat an outer node as innermost until the next
        // registration re-sorted it — a silent, intermittent wrong-target regression.
        when (node) {
            is ShortcutBindingNode -> {
                focusedBindingNodes.remove(node)
                bindingSnapshot = sortedSnapshot(focusedBindingNodes)
            }
            is ShortcutClaimNode -> {
                focusedClaimNodes.remove(node)
                claimSnapshot = sortedSnapshot(focusedClaimNodes)
            }
            is RawKeyClaimNode -> {
                focusedRawClaimNodes.remove(node)
                rawClaimSnapshot = sortedSnapshot(focusedRawClaimNodes)
            }
        }
    }

    /**
     * Outermost-first by nesting depth (the engine treats the LAST entries as innermost), matching the pre-order
     * contract of the dispatch tests. Depth is recomputed on every registration change, so tree edits that fire focus
     * or attach events can never leave a stale order behind; the sort is stable, so registration order breaks ties.
     */
    private fun <T : ShortcutRegistrarNode> sortedSnapshot(nodes: List<T>): List<T> =
        nodes.sortedBy(ShortcutRegistrarNode::nestingDepth)

    internal fun engineBindings(): List<EngineBinding> {
        val snapshot = bindingSnapshot
        // The context is needed only by bindings that derive their state from it, so build it at most once, and
        // never when no binding has an update block: this runs on every dispatch and every presentation sample,
        // and both the standalone tree traversal and the bridge data-context lookup that back it cost something.
        val context = if (snapshot.any { it.update != null }) state.currentActionContext() else ActionContext.Empty
        return snapshot.map { node ->
            // Evaluate the AnAction.update()-equivalent block against the imperative context (the same shared
            // evaluation the reactive presentation runs against the live context), so dispatch enablement and
            // reactive presentation can never diverge on what a block decides.
            val evaluated = evaluateBindingUpdate(node, context)
            EngineBinding(
                actionId = node.action.id,
                enabled = evaluated.enabled,
                blocksOuterBindings = node.blocksOuterBindings,
                origin = node.action.title,
                repeatPolicy = node.repeatPolicy,
                presentationOverride = evaluated.override,
                onInvoke = node.onInvoke,
            )
        }
    }

    /** The current focused-binding snapshot (outermost-first), for the reactive presentation resolve. */
    internal fun bindingNodesSnapshot(): List<ShortcutBindingNode> = bindingSnapshot

    /**
     * The live value contributed for [name] by the focused `Modifier.provideData` nodes, resolved nearest-first with
     * short-circuit: the innermost focused provider that supplies [name] wins (the same nearest-provider-wins
     * precedence [collectActionContext] materializes), and the walk stops there so a control never subscribes to
     * providers farther out than its resolver. Run inside the reactive presentation derivation, the provider lambdas'
     * snapshot reads become that control's per-key dependencies.
     *
     * **Threading:** walks the Compose node tree, so it runs on the surface's UI thread, like dispatch.
     */
    internal fun liveDataFor(name: String): Any? {
        val focused = ArrayList<DataProviderNode>()
        @Suppress("DEPRECATION")
        traverseDescendants(DataProviderNode) { node ->
            // Filter by focus per node rather than skipping unfocused subtrees: a focused inner provider under an outer
            // one that is momentarily not reporting focus (a transient during focus propagation, seen when this runs in
            // a recomposition rather than the settled tree) must still be visited, or its datum would vanish for a
            // frame.
            if (node is DataProviderNode && node.hasFocus) focused.add(node)
            TraversableNode.Companion.TraverseDescendantsAction.ContinueTraversal
        }
        // Pre-order collects outermost-first; walk reversed so the nearest provider that supplies the key wins and the
        // search short-circuits before reaching providers farther out.
        for (index in focused.indices.reversed()) {
            val probe = SingleKeyDataProbe(name)
            focused[index].dataProvider(probe)
            if (probe.found) return probe.value
        }
        return null
    }

    internal fun engineClaims(): List<EngineClaim> =
        claimSnapshot.map { node ->
            EngineClaim(
                sequence = node.sequence,
                enabled = node.enabled,
                blocksOuterClaims = node.blocksOuterClaims,
                repeatPolicy = node.repeatPolicy,
                onInvoke = node.onInvoke,
            )
        }

    /** The innermost registered raw claim matching [event]; raw claims are deliberately local, enabled-only. */
    internal fun rawClaimFor(event: KeyEvent): RawKeyClaimNode? =
        rawClaimSnapshot.lastOrNull { it.enabled && it.matcher(event) }

    /**
     * Collects the focused subtree's `Modifier.provideData` values into an [ActionContext], generalizing the same
     * focused-provider traversal the IJPL bridge feeds into `UiDataProvider.uiDataSnapshot`: it visits provider nodes
     * pre-order, skips subtrees that do not have focus, and lets a nearer (later-visited, inner) provider overwrite an
     * outer one for the same key — the standalone reading of the platform's nearest-provider-wins precedence.
     *
     * **Threading:** walks the Compose node tree, so it must run on the surface's UI thread, like dispatch.
     */
    internal fun collectActionContext(): ActionContext {
        val recording = RecordingDataProviderContext()
        @Suppress("DEPRECATION")
        traverseDescendants(DataProviderNode) { node ->
            if (node is DataProviderNode) {
                if (!node.hasFocus) {
                    return@traverseDescendants TraversableNode.Companion.TraverseDescendantsAction
                        .SkipSubtreeAndContinueTraversal
                }
                node.dataProvider(recording)
            }
            TraversableNode.Companion.TraverseDescendantsAction.ContinueTraversal
        }
        return ActionContext.of(recording.values)
    }

    /**
     * The host-state contract's focus-loss reset: an armed chord or pending typed suppression must not survive focus
     * leaving the surface, or returning focus would encounter stale pending state.
     */
    override fun onFocusEvent(focusState: FocusState) {
        val hasFocus = focusState.hasFocus
        if (subtreeHadFocus && !hasFocus) state.reset()
        subtreeHadFocus = hasFocus
        // Focus moving between provider subtrees changes which `provideData` values are live without any binding
        // registering or deregistering (the bindings may sit on a shared ancestor). The reactive presentation cannot
        // observe the tree walk, so signal it here; binding-driven focus changes already bump through registration.
        state.bumpPresentationRevision()
    }

    override fun onAttach() {
        uiThread = Thread.currentThread()
        state.attachRoot(this)
    }

    override fun onDetach() {
        state.detachRoot(this)
        uiThread = null
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

/**
 * A [DataProviderContext] that records provided values into [values] instead of sinking them into a platform data
 * context. Lazy values are resolved eagerly: the collected context is a point-in-time snapshot, so there is nothing to
 * defer. Later writes win, so an inner focused provider overrides an outer one for the same key.
 */
private class RecordingDataProviderContext : DataProviderContext {
    val values: MutableMap<String, Any?> = LinkedHashMap()

    override fun <TValue : Any> set(key: String, value: TValue?) {
        values[key] = value
    }

    override fun <TValue : Any> lazy(key: String, initializer: () -> TValue?) {
        values[key] = initializer()
    }
}

/**
 * A [DataProviderContext] that captures only the value for a single [name], for the nearest-first short-circuit walk in
 * [ShortcutResolverRootNode.liveDataFor]. Running the provider lambda still reads whatever snapshot state it touches
 * (that is what establishes the reactive dependency); this only avoids materializing the keys the caller did not ask
 * for. [found] distinguishes "provided null" from "not provided", so an explicit null value stops the walk.
 */
private class SingleKeyDataProbe(private val name: String) : DataProviderContext {
    var found: Boolean = false
        private set

    var value: Any? = null
        private set

    override fun <TValue : Any> set(key: String, value: TValue?) {
        if (key == name) {
            found = true
            this.value = value
        }
    }

    override fun <TValue : Any> lazy(key: String, initializer: () -> TValue?) {
        if (key == name) {
            found = true
            value = initializer()
        }
    }
}

/**
 * The standalone reactive [ActionContext]: each `get` reads live through the focused `Modifier.provideData` values via
 * [ShortcutResolverRootNode.liveDataFor], per key. Unlike [ActionContext.of], nothing is materialized up front — that
 * is what lets a `derivedStateOf` evaluating an `update` block against this context establish a per-key dependency on
 * the snapshot state behind the providers, rather than depending on the whole context at once.
 */
internal class LiveActionContext(private val root: ShortcutResolverRootNode) : ActionContext {
    @Suppress("UNCHECKED_CAST") override fun <T> get(key: ActionContextKey<T>): T? = root.liveDataFor(key.name) as T?
}

/** The result of evaluating a binding's `update` block: the effective enablement and presentation override. */
internal class EvaluatedBinding(val enabled: Boolean, val override: ActionPresentationOverride)

/**
 * Evaluates a binding's `update` block against [context], seeded from the binding's static configuration so a block
 * need only change what depends on the context — the single evaluation shared by the imperative dispatch resolve
 * ([JewelShortcutHostState.engineBindings]) and the reactive presentation resolve
 * ([JewelShortcutHostState.reactivePresentationOf]). A binding with no block keeps its static enablement and override
 * verbatim.
 */
internal fun evaluateBindingUpdate(node: ShortcutBindingNode, context: ActionContext): EvaluatedBinding {
    val update = node.update ?: return EvaluatedBinding(node.enabled, node.presentationOverride)
    val base = node.presentationOverride
    val scope =
        ActionUpdateScope(
            context = context,
            enabled = node.enabled,
            visible = (base.visible as? PresentationValue.Set)?.value ?: true,
            selected = (base.selected as? PresentationValue.Set)?.value,
        )
    scope.update()
    return EvaluatedBinding(
        enabled = scope.enabled,
        override =
            base.copy(
                visible = PresentationValue.Set(scope.visible),
                selected = scope.selected?.let { PresentationValue.Set(it) } ?: base.selected,
            ),
    )
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
