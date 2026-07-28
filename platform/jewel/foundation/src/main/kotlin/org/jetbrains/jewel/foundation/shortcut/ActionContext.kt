// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/**
 * A typed key into an [ActionContext].
 *
 * Its [name] is the identity: two keys with the same name are the same key, and the name is what an [ActionContext]
 * looks a value up by. Names deliberately match the IJPL data-key naming so a context can round-trip the IntelliJ
 * bridge — the bridge reads and writes the platform data context under this same name, so a key created here resolves
 * platform data authored elsewhere (use `"project"`, `"virtualFile"`, and so on when interoperability matters).
 *
 * Keys are interned by name, so a control and the code that provides its datum can each `create` the key independently
 * and still agree.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class ActionContextKey<T> private constructor(public val name: String) {
    override fun equals(other: Any?): Boolean = this === other || (other is ActionContextKey<*> && other.name == name)

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = "ActionContextKey($name)"

    /** Factory for interned keys; call [create] rather than constructing keys directly. */
    public companion object {
        private val interned = ConcurrentHashMap<String, ActionContextKey<*>>()

        /** The key named [name], interned so independent callers using the same name get the same key. */
        public fun <T> create(name: String): ActionContextKey<T> {
            @Suppress("UNCHECKED_CAST")
            return interned.computeIfAbsent(name) { ActionContextKey<T>(it) } as ActionContextKey<T>
        }
    }
}

/**
 * The data an action reads to decide its enablement, visibility, and dynamic content — Jewel's typed facade over a data
 * context, the host-neutral analogue of the `DataContext` an IJPL `AnAction.update()` consults.
 *
 * The concrete backing depends on the surface, but the read shape does not: in the IntelliJ bridge it wraps the
 * platform `DataContext`, so project, editor, selection and every other platform datum are visible with the platform's
 * nearest-provider-wins precedence; standalone it exposes the data the focused `Modifier.provideData` nodes
 * contributed, where the nearest (innermost focused) provider likewise wins. Components never know which backing they
 * are reading.
 *
 * **Threading:** obtain one through [JewelShortcutHostState.currentActionContext]. The standalone backing walks the
 * focused Compose node tree and so is UI-thread-bound, like the rest of dispatch; the bridge backing is safe wherever
 * the platform lets its data context be read.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public interface ActionContext {
    /** The value contributed for [key], or `null` when no focused provider (or the platform) supplies one. */
    public operator fun <T> get(key: ActionContextKey<T>): T?

    /** The shared empty context and the [of] factory for snapshot-backed contexts. */
    public companion object {
        /** The context that supplies nothing; what a surface with no data providers reads. */
        public val Empty: ActionContext =
            object : ActionContext {
                override fun <T> get(key: ActionContextKey<T>): T? = null
            }

        /**
         * A context backed by an immutable snapshot of values keyed by [ActionContextKey.name]. This is the standalone
         * backing, built from the focused providers; exposed so hosts and tests can supply data directly.
         */
        public fun of(values: Map<String, Any?>): ActionContext =
            if (values.isEmpty()) Empty else MapActionContext(values.toMap())
    }
}

/**
 * Value backing for [ActionContext.of]; equality on the snapshot so a re-collected identical context compares equal.
 */
private class MapActionContext(private val values: Map<String, Any?>) : ActionContext {
    @Suppress("UNCHECKED_CAST") override fun <T> get(key: ActionContextKey<T>): T? = values[key.name] as T?

    override fun equals(other: Any?): Boolean = other is MapActionContext && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "ActionContext(${values.keys})"
}
