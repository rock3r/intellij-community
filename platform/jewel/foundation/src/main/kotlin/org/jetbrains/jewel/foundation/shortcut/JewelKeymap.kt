// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/**
 * A named keyboard scheme: effective bindings are this scheme's own bindings applied over its [parent]'s,
 * with explicit hide markers able to remove inherited bindings (the standalone analogue of IJPL keymap
 * inheritance).
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public interface JewelKeymap {
    public val name: String
    public val parent: JewelKeymap?

    /** Increments on every mutation of this scheme (not of its parent). */
    public val modificationCount: StateFlow<Long>

    public fun shortcutsFor(action: JewelActionId): List<JewelKeySequence>

    public fun actionIdsFor(sequence: JewelKeySequence): List<JewelActionId>

    /** Action IDs having a two-stroke sequence whose first stroke is [first]; needed for chord dispatch. */
    public fun actionIdsForPrefix(first: JewelKeyStroke): List<JewelActionId>

    public fun actionIds(): Set<JewelActionId>

    public fun conflicts(sequence: JewelKeySequence): List<JewelActionId> = actionIdsFor(sequence)
}

@ApiStatus.Experimental
@ExperimentalJewelApi
public interface MutableJewelKeymap : JewelKeymap {
    public fun bind(action: JewelActionId, sequence: JewelKeySequence)

    public fun unbind(action: JewelActionId, sequence: JewelKeySequence)

    /** Hides a binding inherited from the parent chain without touching the parent scheme. */
    public fun hideInherited(action: JewelActionId, sequence: JewelKeySequence)

    public fun replaceBindings(action: JewelActionId, sequences: List<JewelKeySequence>)

    public fun removeAllBindings(action: JewelActionId)
}

@ApiStatus.Experimental
@ExperimentalJewelApi
public class InMemoryJewelKeymap(override val name: String, override val parent: JewelKeymap? = null) :
    MutableJewelKeymap {
    private val own = LinkedHashMap<JewelActionId, MutableList<JewelKeySequence>>()
    private val hidden = LinkedHashMap<JewelActionId, MutableSet<JewelKeySequence>>()
    private val replaced = mutableSetOf<JewelActionId>()

    private val _modificationCount = MutableStateFlow(0L)
    override val modificationCount: StateFlow<Long> = _modificationCount.asStateFlow()

    private fun touch() {
        _modificationCount.update { it + 1 }
    }

    @Synchronized
    override fun shortcutsFor(action: JewelActionId): List<JewelKeySequence> {
        val inherited =
            if (action in replaced) {
                emptyList()
            } else {
                (parent?.shortcutsFor(action).orEmpty()).filterNot { it in hidden[action].orEmpty() }
            }
        return inherited + own[action].orEmpty()
    }

    @Synchronized
    override fun actionIdsFor(sequence: JewelKeySequence): List<JewelActionId> =
        actionIds().filter { sequence in shortcutsFor(it) }

    @Synchronized
    override fun actionIdsForPrefix(first: JewelKeyStroke): List<JewelActionId> =
        actionIds().filter { id -> shortcutsFor(id).any { it.second != null && it.first == first } }

    @Synchronized
    override fun actionIds(): Set<JewelActionId> = (parent?.actionIds().orEmpty()) + own.keys

    @Synchronized
    override fun bind(action: JewelActionId, sequence: JewelKeySequence) {
        own.getOrPut(action) { mutableListOf() }.add(sequence)
        touch()
    }

    @Synchronized
    override fun unbind(action: JewelActionId, sequence: JewelKeySequence) {
        own[action]?.remove(sequence)
        touch()
    }

    @Synchronized
    override fun hideInherited(action: JewelActionId, sequence: JewelKeySequence) {
        hidden.getOrPut(action) { mutableSetOf() }.add(sequence)
        touch()
    }

    @Synchronized
    override fun replaceBindings(action: JewelActionId, sequences: List<JewelKeySequence>) {
        replaced.add(action)
        own[action] = sequences.toMutableList()
        touch()
    }

    @Synchronized
    override fun removeAllBindings(action: JewelActionId) {
        replaced.add(action)
        own[action] = mutableListOf()
        touch()
    }

    public companion object {
        /** Base scheme seeded from the registered action definitions' default shortcuts. */
        public fun fromDefaults(name: String, registry: JewelActionRegistry): InMemoryJewelKeymap {
            val keymap = InMemoryJewelKeymap(name)
            for (definition in registry.definitions()) {
                for (sequence in definition.defaultShortcuts) {
                    keymap.bind(definition.action.id, sequence)
                }
            }
            return keymap
        }
    }
}

@ApiStatus.Experimental
@ExperimentalJewelApi
public interface JewelKeymapManager {
    public val activeKeymap: StateFlow<JewelKeymap>
    public val keymaps: StateFlow<List<JewelKeymap>>

    public fun addKeymap(keymap: JewelKeymap)

    public fun removeKeymap(name: String)

    public fun setActiveKeymap(name: String)
}

@ApiStatus.Experimental
@ExperimentalJewelApi
public class DefaultJewelKeymapManager(initial: JewelKeymap) : JewelKeymapManager {
    private val _keymaps = MutableStateFlow(listOf(initial))
    private val _active = MutableStateFlow(initial)

    override val activeKeymap: StateFlow<JewelKeymap> = _active.asStateFlow()
    override val keymaps: StateFlow<List<JewelKeymap>> = _keymaps.asStateFlow()

    override fun addKeymap(keymap: JewelKeymap) {
        _keymaps.update { current -> current.filterNot { it.name == keymap.name } + keymap }
    }

    override fun removeKeymap(name: String) {
        require(_active.value.name != name) { "Cannot remove the active keymap '$name'" }
        _keymaps.update { current -> current.filterNot { it.name == name } }
    }

    override fun setActiveKeymap(name: String) {
        val keymap = _keymaps.value.firstOrNull { it.name == name }
        requireNotNull(keymap) { "Unknown keymap '$name'" }
        _active.value = keymap
    }
}
