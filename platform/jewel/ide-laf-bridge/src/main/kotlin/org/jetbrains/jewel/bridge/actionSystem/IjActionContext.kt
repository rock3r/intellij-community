// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.actionSystem

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.shortcut.ActionContext
import org.jetbrains.jewel.foundation.shortcut.ActionContextKey

/**
 * The IntelliJ backing for [ActionContext]: a thin, read-only facade over a platform [DataContext].
 *
 * Because an [ActionContextKey]'s name equals the corresponding platform `DataKey` name, a lookup is just
 * `dataContext.getData(DataKey.create(name))` — so a Jewel control reads the very same project, editor, selection and
 * caret data an ordinary `AnAction.update()` would, with the platform's own nearest-provider-wins precedence, and no
 * per-key translation table. The data the focused `Modifier.provideData` nodes contributed is visible here too, since
 * the wrapper sinks it into this same context.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class IjActionContext(private val dataContext: DataContext) : ActionContext {
    override fun <T> get(key: ActionContextKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return dataContext.getData(DataKey.create<Any>(key.name)) as T?
    }

    override fun toString(): String = "IjActionContext($dataContext)"
}

/** The platform [DataKey] this key maps onto: same name, so values authored on either side resolve on the other. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public fun <T : Any> ActionContextKey<T>.asDataKey(): DataKey<T> = DataKey.create(name)

/** The Jewel [ActionContextKey] this platform key maps onto: same name, interned so callers converge on one key. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public fun <T : Any> DataKey<T>.asActionContextKey(): ActionContextKey<T> = ActionContextKey.create(name)
