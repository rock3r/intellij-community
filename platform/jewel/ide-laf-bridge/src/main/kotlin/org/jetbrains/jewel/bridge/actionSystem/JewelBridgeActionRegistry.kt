// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.actionSystem

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.rethrowControlFlowException
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.Disposer
import com.intellij.platform.icons.swing.toIconDescriptor
import com.intellij.util.ui.EDT
import java.awt.Component
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.shortcut.ActionPresentation
import org.jetbrains.jewel.foundation.shortcut.ActionPropertyNames
import org.jetbrains.jewel.foundation.shortcut.ActionRegistration
import org.jetbrains.jewel.foundation.shortcut.ActionResolution
import org.jetbrains.jewel.foundation.shortcut.ActionTemplatePresentation
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionDefinition
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.jetbrains.jewel.foundation.shortcut.JewelActionKind
import org.jetbrains.jewel.foundation.shortcut.JewelActionRegistry
import org.jetbrains.jewel.foundation.shortcut.JewelKeySequence
import org.jetbrains.jewel.foundation.shortcut.swingKeyStrokeToJewelKeyStroke
import org.jetbrains.jewel.foundation.util.myLogger

/**
 * The IJPL implementation of [JewelActionRegistry], realizing the attach-or-register lifecycle:
 * - **Startup/declarative**: plugin.xml declares a [JewelActionBridgeAction] under the Jewel action ID; [register] then
 *   *attaches* the definition to that existing action — it never calls `ActionManager.registerAction` for a declared
 *   ID, and closing the registration never unregisters it.
 * - **Runtime/programmatic**: when no action owns the ID, [register] creates and registers a runtime
 *   [JewelActionBridgeAction]; the returned handle unregisters it when the last reference closes. Scope a handle to a
 *   platform lifetime with [closeWith].
 *
 * Identical duplicate definitions are reference-counted; a differing definition for a registered ID fails. An ID owned
 * by a non-Jewel action fails registration — map to existing platform actions explicitly via [JewelActionMappings]
 * instead.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class JewelBridgeActionRegistry(
    private val actionManager: ActionManager,
    /**
     * The panel the Jewel controls live in. Presentations are updated against *this* component's data context — the
     * same context [JewelBridgeActionInvoker] executes against — so `update()` and `actionPerformed()` can never
     * disagree about what the action is acting on.
     */
    private val hostComponent: Component?,
) : JewelActionRegistry {
    /** Creates a registry backed by the global [ActionManager] with no panel context. */
    public constructor() : this(ActionManager.getInstance(), null)

    /** Creates a registry backed by [actionManager] with no panel context. */
    public constructor(actionManager: ActionManager) : this(actionManager, null)

    /** Creates a registry backed by the global [ActionManager] bound to [hostComponent]. */
    public constructor(hostComponent: Component?) : this(ActionManager.getInstance(), hostComponent)

    private class Entry(val definition: JewelActionDefinition, val isRuntimeOwned: Boolean) {
        var refCount: Int = 0
    }

    private class SharedRuntimeEntry(val definition: JewelActionDefinition, val action: JewelActionBridgeAction) {
        var refCount: Int = 0
    }

    private val logger = myLogger()
    private val reportedSampleFailures = ConcurrentHashMap.newKeySet<JewelActionId>()
    private val entries = ConcurrentHashMap<JewelActionId, Entry>()

    override fun register(definition: JewelActionDefinition): ActionRegistration {
        val id = definition.action.id
        synchronized(sharedRuntimeEntries) {
            val existing = entries[id]
            if (existing != null) {
                check(existing.definition == definition) {
                    "Action '${id.value}' is already registered with a different descriptor"
                }
                if (existing.isRuntimeOwned && actionManager.getAction(id.value) == null) {
                    sharedRuntimeEntries[actionManager]?.get(id)?.let { shared ->
                        actionManager.registerAction(id.value, shared.action)
                    }
                }
                existing.refCount++
            } else {
                val sharedForManager = sharedRuntimeEntries[actionManager]
                val shared = sharedForManager?.get(id)
                val declared = actionManager.getAction(id.value)
                val entry =
                    if (shared != null) {
                        check(shared.definition == definition) {
                            "Action '${id.value}' is already registered with a different descriptor"
                        }
                        when {
                            declared == null -> {
                                actionManager.registerAction(id.value, shared.action)
                                shared.refCount++
                                Entry(definition, isRuntimeOwned = true)
                            }
                            declared === shared.action -> {
                                shared.refCount++
                                Entry(definition, isRuntimeOwned = true)
                            }
                            else -> {
                                // External code replaced our runtime action; track locally without clobbering or
                                // unregistering the foreign occupant.
                                Entry(definition, isRuntimeOwned = false)
                            }
                        }
                    } else {
                        when {
                            declared is JewelActionBridgeAction -> Entry(definition, isRuntimeOwned = false)
                            declared == null -> {
                                val action = JewelActionBridgeAction()
                                action.templatePresentation.setText(definition.action.title, false)
                                actionManager.registerAction(id.value, action)
                                val map =
                                    sharedForManager
                                        ?: HashMap<JewelActionId, SharedRuntimeEntry>().also {
                                            sharedRuntimeEntries[actionManager] = it
                                        }
                                map[id] = SharedRuntimeEntry(definition, action).apply { refCount = 1 }
                                Entry(definition, isRuntimeOwned = true)
                            }
                            else ->
                                error(
                                    "Action ID '${id.value}' is owned by ${declared.javaClass.name}; " +
                                        "use JewelActionMappings to map to an existing platform action"
                                )
                        }
                    }
                entry.refCount = 1
                entries[id] = entry
            }
        }

        return object : ActionRegistration {
            override val action: JewelAction = definition.action
            private var closed = false

            override fun close() {
                synchronized(sharedRuntimeEntries) {
                    if (closed) return
                    closed = true
                    val entry = entries[id] ?: return
                    entry.refCount--
                    if (entry.refCount <= 0) {
                        entries.remove(id)
                        if (entry.isRuntimeOwned) {
                            val sharedMap = sharedRuntimeEntries[actionManager]
                            val shared = sharedMap?.get(id)
                            if (shared != null) {
                                shared.refCount--
                                if (shared.refCount <= 0) {
                                    sharedMap.remove(id)
                                    if (sharedMap.isEmpty()) sharedRuntimeEntries.remove(actionManager)
                                    if (actionManager.getAction(id.value) === shared.action) {
                                        actionManager.unregisterAction(id.value)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun definition(id: JewelActionId): JewelActionDefinition? {
        entries[id]?.definition?.let {
            return it
        }
        val targetId = JewelActionMappings.ideActionIdFor(id) ?: id.value
        val key = id to targetId
        adoptedDefinitions[key]?.let {
            return it
        }
        // Do not memoize misses forever so dynamically registered IDE actions remain adoptable later.
        val adopted = adoptDeclaredAction(id, targetId) ?: return null
        return adoptedDefinitions.putIfAbsent(key, adopted) ?: adopted
    }

    override fun definitions(): List<JewelActionDefinition> = entries.values.map { it.definition }

    override fun shortcutsFor(id: JewelActionId): List<JewelKeySequence> {
        if (ApplicationManager.getApplication() == null) return emptyList()
        val activeKeymap = KeymapManager.getInstance()?.activeKeymap ?: return emptyList()
        val targetId = JewelActionMappings.ideActionIdFor(id) ?: id.value
        return activeKeymap.getShortcuts(targetId).mapNotNull { shortcut ->
            val keyboard = shortcut as? KeyboardShortcut ?: return@mapNotNull null
            val first = swingKeyStrokeToJewelKeyStroke(keyboard.firstKeyStroke) ?: return@mapNotNull null
            val second =
                keyboard.secondKeyStroke?.let { secondStroke ->
                    swingKeyStrokeToJewelKeyStroke(secondStroke) ?: return@mapNotNull null
                }
            JewelKeySequence(first, second)
        }
    }

    /**
     * Definitions synthesised from actions this registry never registered, so a component bound to an ID that the IDE
     * already declares still resolves a presentation. Keyed by `(id, targetId)` so installing or overriding
     * [JewelActionMappings] invalidates earlier lookups automatically.
     */
    private val adoptedDefinitions = ConcurrentHashMap<Pair<JewelActionId, String>, JewelActionDefinition>()

    /**
     * One presentation per adopted action, mirroring how IJPL keeps a per-place `Presentation` alive across update
     * cycles instead of recreating it. `update()` writes into these, and each sample reads the result.
     */
    private val presentationFactory = PresentationFactory()

    /**
     * Runs the platform's `update()` for [id] and maps the resulting per-place `Presentation` into Jewel's own — the
     * layer that carries whatever the action decided this cycle, as opposed to what it authored once.
     *
     * Must run platform updates on the EDT (with [hostComponent]'s data context when on EDT; off-EDT callers fall back
     * to [DataContext.EMPTY_CONTEXT]). Returns `null` for actions this registry did not adopt, and on any failure
     * (logged at warn level) so a misbehaving `update()` degrades cleanly to the template.
     */
    override fun sampledPresentation(id: JewelActionId): ActionPresentation? {
        val targetId = JewelActionMappings.ideActionIdFor(id) ?: id.value
        val declared = actionManager.getAction(targetId) ?: return null
        if (declared is JewelActionBridgeAction) return null // Jewel owns this one; its state comes from the binding.

        val presentation = presentationFactory.getPresentation(declared)
        // Update against the host panel's data context when on EDT — the very context tryToExecute derives when the
        // action runs — and degrade to EMPTY_CONTEXT when sampled off-EDT because DataManager.getDataContext(Component)
        // asserts EDT.
        val context =
            if (hostComponent != null && EDT.isCurrentThreadEdt()) {
                DataManager.getInstance().getDataContext(hostComponent)
            } else {
                DataContext.EMPTY_CONTEXT
            }
        val event =
            AnActionEvent.createEvent(declared, context, presentation, JEWEL_ACTION_PLACE, ActionUiKind.NONE, null)
        @Suppress("TooGenericExceptionCaught", "DEPRECATION")
        try {
            ActionUtil.updateAction(declared, event)
        } catch (e: Throwable) {
            rethrowControlFlowException(e)
            if (reportedSampleFailures.add(id)) {
                logger.warn("Failed to sample presentation for action '${id.value}' (further failures at debug)", e)
            } else {
                logger.debug("Failed to sample presentation for action '${id.value}'", e)
            }
            return null
        }

        return ActionPresentation(
            text = presentation.text ?: declared.templatePresentation.text ?: id.value,
            description = presentation.description,
            enabled = presentation.isEnabled,
            visible = presentation.isVisible,
            icon = presentation.icon?.toIconDescriptor(),
            properties =
                buildMap {
                    if (Toggleable.isSelected(presentation)) put(ActionPropertyNames.Selected, true)
                    if (presentation.getClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR) == true) {
                        put(ActionPropertyNames.ShowTextInToolbar, true)
                    }
                    if (presentation.getClientProperty(ActionUtil.HIDE_DROPDOWN_ICON) == true) {
                        put(ActionPropertyNames.HideDropdownIcon, true)
                    }
                },
            resolution = ActionResolution.Resolved,
        )
    }

    /**
     * Builds a [JewelActionDefinition] from an action the IDE already declares, carrying that action's template
     * presentation across: its text, description and icon are what `plugin.xml` (or the action's constructor) authored,
     * so a Jewel control renders a declared action exactly as a Swing one does.
     *
     * The icon crosses as a cross-frontend descriptor via [toIconDescriptor], which is what makes one authored icon
     * renderable by both toolkits. Shortcuts stay empty: a declared action's bindings live in the IDE keymap, which the
     * bridge defers to via [shortcutsFor].
     */
    private fun adoptDeclaredAction(id: JewelActionId, targetId: String): JewelActionDefinition? {
        val declared = actionManager.getAction(targetId) ?: return null
        val template = declared.templatePresentation
        val action =
            JewelAction(
                id = id,
                template =
                    ActionTemplatePresentation(
                        text = template.text ?: id.value,
                        description = template.description,
                        icon = template.icon?.toIconDescriptor(),
                    ),
                kind = if (declared is Toggleable) JewelActionKind.Toggle else JewelActionKind.Command,
            )
        return JewelActionDefinition(action)
    }

    private companion object {
        // Process-wide ref counting per ActionManager + action ID so closing one panel keeps runtime actions alive
        // while another panel still holds a registration; WeakHashMap prevents leaking test ActionManager instances.
        private val sharedRuntimeEntries = WeakHashMap<ActionManager, MutableMap<JewelActionId, SharedRuntimeEntry>>()
    }
}

/**
 * Scopes any [AutoCloseable] — typically an [ActionRegistration] — to a platform [Disposable] lifetime without adding
 * IJPL types to the common registry API. Manual `close()` remains safe: closing is idempotent, so parent disposal
 * merely performs a harmless second close.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public fun <T : AutoCloseable> T.closeWith(parent: Disposable): T = also { closeable ->
    Disposer.register(parent) { closeable.close() }
}
