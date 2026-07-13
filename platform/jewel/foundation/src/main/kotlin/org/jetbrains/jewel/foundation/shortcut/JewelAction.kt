// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/** Stable identifier for a Jewel action; use reverse-DNS names for application commands. */
@ApiStatus.Experimental
@ExperimentalJewelApi
@JvmInline
public value class JewelActionId(public val value: String)

@ApiStatus.Experimental
@ExperimentalJewelApi
public enum class JewelActionKind {
    Command,
    Toggle,
}

/** Host-neutral action identity and default presentation. Immutable; dynamic state lives on bindings. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class JewelAction(
    val id: JewelActionId,
    val title: String,
    val category: String? = null,
    val kind: JewelActionKind = JewelActionKind.Command,
)

/** The complete host-neutral catalog item: semantics plus default keyboard bindings. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class JewelActionDefinition(
    val action: JewelAction,
    val defaultShortcuts: List<JewelKeySequence> = emptyList(),
)

@ApiStatus.Experimental
@ExperimentalJewelApi
public interface ActionRegistration : AutoCloseable {
    public val action: JewelAction

    override fun close()
}

/**
 * Registry of action definitions for one host. In standalone it is the application action catalog; the IJPL
 * bridge attaches definitions to matching declared bridge actions or registers runtime ones.
 *
 * Identical duplicate registrations are reference-counted; a differing descriptor for an already-registered
 * ID fails with [IllegalStateException].
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public interface JewelActionRegistry {
    public fun register(definition: JewelActionDefinition): ActionRegistration

    public fun definition(id: JewelActionId): JewelActionDefinition?

    public fun definitions(): List<JewelActionDefinition>
}

@ApiStatus.Experimental
@ExperimentalJewelApi
public class DefaultJewelActionRegistry : JewelActionRegistry {
    private class Entry(val definition: JewelActionDefinition) {
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
                entries[id] = Entry(definition).also { it.refCount = 1 }
            }
        }

        val closed = AtomicBoolean(false)
        return object : ActionRegistration {
            override val action: JewelAction = definition.action

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                synchronized(entries) {
                    val entry = entries[id] ?: return
                    entry.refCount--
                    if (entry.refCount <= 0) entries.remove(id)
                }
            }
        }
    }

    override fun definition(id: JewelActionId): JewelActionDefinition? = entries[id]?.definition

    override fun definitions(): List<JewelActionDefinition> = entries.values.map { it.definition }
}
