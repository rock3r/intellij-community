// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.actionSystem

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.util.Disposer
import com.intellij.platform.icons.swing.toNewIcon
import java.awt.Component
import java.util.Optional
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
    private val actionManager: ActionManager = ActionManager.getInstance(),
    /**
     * The panel the Jewel controls live in. Presentations are updated against *this* component's data context — the
     * same context [JewelBridgeActionInvoker] executes against — so `update()` and `actionPerformed()` can never
     * disagree about what the action is acting on.
     */
    private val hostComponent: Component? = null,
) : JewelActionRegistry {
    private class Entry(val definition: JewelActionDefinition, val runtimeAction: JewelActionBridgeAction?) {
        var refCount: Int = 0
    }

    private val entries = ConcurrentHashMap<JewelActionId, Entry>()

    override fun register(definition: JewelActionDefinition): ActionRegistration {
        val id = definition.action.id
        synchronized(entries) {
            val existing = entries[id]
            if (existing != null) {
                check(existing.definition == definition) {
                    "Action '${id.value}' is already registered with a different descriptor"
                }
                existing.refCount++
            } else {
                val declared = actionManager.getAction(id.value)
                val entry =
                    when {
                        declared is JewelActionBridgeAction -> Entry(definition, runtimeAction = null)
                        declared == null -> {
                            val action = JewelActionBridgeAction()
                            action.templatePresentation.text = definition.action.title
                            actionManager.registerAction(id.value, action)
                            Entry(definition, runtimeAction = action)
                        }
                        else ->
                            error(
                                "Action ID '${id.value}' is owned by ${declared.javaClass.name}; " +
                                    "use JewelActionMappings to map to an existing platform action"
                            )
                    }
                entry.refCount = 1
                entries[id] = entry
            }
        }

        return object : ActionRegistration {
            override val action: JewelAction = definition.action
            private var closed = false

            override fun close() {
                synchronized(entries) {
                    if (closed) return
                    closed = true
                    val entry = entries[id] ?: return
                    entry.refCount--
                    if (entry.refCount <= 0) {
                        entries.remove(id)
                        if (entry.runtimeAction != null) actionManager.unregisterAction(id.value)
                    }
                }
            }
        }
    }

    override fun definition(id: JewelActionId): JewelActionDefinition? =
        entries[id]?.definition ?: adoptedDefinitions.computeIfAbsent(id) { adoptDeclaredAction(it) }.orElse(null)

    override fun definitions(): List<JewelActionDefinition> = entries.values.map { it.definition }

    /**
     * Definitions synthesised from actions this registry never registered, so a component bound to an ID that the IDE
     * already declares still resolves a presentation. Cached because [definition] is on the presentation-sampling path,
     * which the platform drives on its own update cadence; `Optional` records the misses too, so an unknown ID is not
     * looked up again on every sample.
     */
    private val adoptedDefinitions = ConcurrentHashMap<JewelActionId, Optional<JewelActionDefinition>>()

    /**
     * One presentation per adopted action, mirroring how IJPL keeps a per-place `Presentation` alive across update
     * cycles instead of recreating it. `update()` writes into these, and each sample reads the result.
     */
    private val presentationFactory = PresentationFactory()

    /**
     * Runs the platform's `update()` for [id] and maps the resulting per-place `Presentation` into Jewel's own — the
     * layer that carries whatever the action decided this cycle, as opposed to what it authored once.
     *
     * Everything IJPL models as a `Presentation` field crosses as a field; everything it models as a client property
     * crosses in [ActionPresentation.properties] under the same name, so toggle state arrives as
     * [org.jetbrains.jewel.foundation.shortcut.ActionPropertyNames.Selected] without either side special-casing it.
     *
     * Returns null for actions this registry did not adopt, and on any failure: a presentation is advisory, so a
     * misbehaving `update()` must degrade to the template rather than break rendering.
     */
    override fun sampledPresentation(id: JewelActionId): ActionPresentation? {
        val declared = actionManager.getAction(id.value) ?: return null
        if (declared is JewelActionBridgeAction) return null // Jewel owns this one; its state comes from the binding.

        val presentation = presentationFactory.getPresentation(declared)
        // Update against the host panel's data context — the very context tryToExecute derives when the action runs.
        // This used to poll dataContextFromFocusAsync with a zero timeout, which silently degraded to EMPTY_CONTEXT
        // whenever the async context wasn't already resolved: the action then computed itself disabled (no project, no
        // editor, no selection) while still executing correctly against the panel. One source now, so update() and
        // actionPerformed() always agree.
        val context = hostComponent?.let { DataManager.getInstance().getDataContext(it) } ?: DataContext.EMPTY_CONTEXT
        val event =
            AnActionEvent.createEvent(declared, context, presentation, JEWEL_ACTION_PLACE, ActionUiKind.NONE, null)
        runCatching { ActionUtil.updateAction(declared, event) }
            .getOrElse {
                return null
            }

        return ActionPresentation(
            text = presentation.text ?: declared.templatePresentation.text ?: id.value,
            description = presentation.description,
            enabled = presentation.isEnabled,
            visible = presentation.isVisible,
            icon = presentation.icon?.toNewIcon(),
            properties = buildMap { if (Toggleable.isSelected(presentation)) put(ActionPropertyNames.Selected, true) },
            resolution = ActionResolution.Resolved,
        )
    }

    /**
     * Builds a [JewelActionDefinition] from an action the IDE already declares, carrying that action's template
     * presentation across: its text, description and icon are what `plugin.xml` (or the action's constructor) authored,
     * so a Jewel control renders a declared action exactly as a Swing one does.
     *
     * The icon crosses as a cross-frontend descriptor via [toNewIcon], which is what makes one authored icon renderable
     * by both toolkits. Shortcuts stay empty: a declared action's bindings live in the IDE keymap, which the bridge
     * defers to rather than duplicating.
     */
    private fun adoptDeclaredAction(id: JewelActionId): Optional<JewelActionDefinition> {
        val declared = actionManager.getAction(id.value) ?: return Optional.empty()
        val template = declared.templatePresentation
        val action =
            JewelAction(
                id = id,
                template =
                    ActionTemplatePresentation(
                        text = template.text ?: id.value,
                        description = template.description,
                        icon = template.icon?.toNewIcon(),
                    ),
                kind = if (declared is Toggleable) JewelActionKind.Toggle else JewelActionKind.Command,
            )
        return Optional.of(JewelActionDefinition(action))
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
