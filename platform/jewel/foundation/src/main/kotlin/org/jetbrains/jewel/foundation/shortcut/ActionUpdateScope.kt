// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

/**
 * The scope a binding's `update` block runs in — Jewel's stand-in for the body of an IJPL `AnAction.update()`.
 *
 * A block reads the focused surface's [context] and sets the mutable properties to decide the action's per-place state:
 * [enabled] gates execution and rendering, [visible] gates rendering only (a hidden but enabled action stays
 * keymap-invocable, as in the platform), and [selected] carries toggle state. Each property is seeded from the
 * binding's static configuration, so a block need only change what depends on the context.
 *
 * The block is evaluated on the surface's UI thread whenever dispatch resolves or a presentation is sampled; keep it
 * fast and side-effect-free — it is a pure function of [context], not a place to perform work.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public class ActionUpdateScope
internal constructor(
    /** The focused surface's data, the same [ActionContext] the action would act against. */
    public val context: ActionContext,
    /** Whether the action can run here; gates both keyboard dispatch and control enablement. */
    public var enabled: Boolean,
    /** Whether the action renders here; a hidden but [enabled] action stays keymap-invocable. */
    public var visible: Boolean,
    /** Toggle state for a toggle action, or `null` to leave it to the binding's own override. */
    public var selected: Boolean?,
)
