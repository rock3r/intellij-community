// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.actionSystem

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.util.Disposer
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.shortcut.ActionRegistration
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionDefinition
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.jetbrains.jewel.foundation.shortcut.JewelActionRegistry

/**
 * The IJPL implementation of [JewelActionRegistry], realizing the attach-or-register lifecycle:
 *
 * - **Startup/declarative**: plugin.xml declares a [JewelActionBridgeAction] under the Jewel action ID;
 *   [register] then *attaches* the definition to that existing action — it never calls
 *   `ActionManager.registerAction` for a declared ID, and closing the registration never unregisters it.
 * - **Runtime/programmatic**: when no action owns the ID, [register] creates and registers a runtime
 *   [JewelActionBridgeAction]; the returned handle unregisters it when the last reference closes. Scope a
 *   handle to a platform lifetime with [closeWith].
 *
 * Identical duplicate definitions are reference-counted; a differing definition for a registered ID fails.
 * An ID owned by a non-Jewel action fails registration — map to existing platform actions explicitly via
 * [JewelActionMappings] instead.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class JewelBridgeActionRegistry(
    private val actionManager: ActionManager = ActionManager.getInstance()
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

    override fun definition(id: JewelActionId): JewelActionDefinition? = entries[id]?.definition

    override fun definitions(): List<JewelActionDefinition> = entries.values.map { it.definition }
}

/**
 * Scopes any [AutoCloseable] — typically an [ActionRegistration] — to a platform [Disposable] lifetime
 * without adding IJPL types to the common registry API. Manual `close()` remains safe: closing is
 * idempotent, so parent disposal merely performs a harmless second close.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public fun <T : AutoCloseable> T.closeWith(parent: Disposable): T = also { closeable ->
    Disposer.register(parent) { closeable.close() }
}
