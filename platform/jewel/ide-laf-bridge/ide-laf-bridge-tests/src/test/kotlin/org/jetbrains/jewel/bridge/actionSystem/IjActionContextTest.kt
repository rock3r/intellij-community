// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.actionSystem

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import org.jetbrains.jewel.foundation.shortcut.ActionContextKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the bridge facade over a platform [DataContext]: a Jewel [ActionContextKey] reads the platform datum authored
 * under the same name, and the key conversions round-trip by name in both directions.
 */
internal class IjActionContextTest {
    private val selectionSize = DataKey.create<Int>("test.bridge.selectionSize")
    private val label = DataKey.create<String>("test.bridge.label")

    /** A [DataContext] is effectively a SAM over `getData(String)`; the typed overload defaults to it. */
    private fun dataContextOf(vararg pairs: Pair<DataKey<*>, Any?>): DataContext {
        val byName = pairs.associate { it.first.name to it.second }
        return DataContext { dataId -> byName[dataId] }
    }

    @Test
    fun `reads platform data by matching key name`() {
        val context = IjActionContext(dataContextOf(selectionSize to 5))

        assertEquals(5, context[selectionSize.asActionContextKey()])
        assertEquals(5, context[ActionContextKey.create<Int>("test.bridge.selectionSize")])
        assertNull(context[label.asActionContextKey()])
    }

    @Test
    fun `key conversions round-trip by name`() {
        assertEquals(selectionSize.name, selectionSize.asActionContextKey().name)
        assertEquals(selectionSize.name, selectionSize.asActionContextKey().asDataKey().name)

        val jewelKey = ActionContextKey.create<String>("test.bridge.label")
        assertEquals(jewelKey.name, jewelKey.asDataKey().name)
        // The platform key interns by name too, so a converted key is the same instance the platform would create.
        assertSame(label, jewelKey.asDataKey())
    }
}
