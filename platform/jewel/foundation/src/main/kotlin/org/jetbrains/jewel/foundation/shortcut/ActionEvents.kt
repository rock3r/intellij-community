// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.foundation.shortcut

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi

@ApiStatus.Experimental
@ExperimentalJewelApi
public sealed interface ActionTrigger {
    public data class Keyboard(val sequence: JewelKeySequence?) : ActionTrigger

    public data object Pointer : ActionTrigger

    public data object Programmatic : ActionTrigger
}

/**
 * One completed Jewel-owned invocation. "Executed" has one narrow meaning: a resolved Jewel handler
 * returned normally. It is not a business-operation completion signal. Actions mapped to existing IJPL
 * actions never emit here — observe those through ordinary platform facilities.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public data class ActionInvocation(
    val actionId: JewelActionId?,
    val sequence: JewelKeySequence?,
    val trigger: ActionTrigger,
)

/** Host-neutral observation point for Presentation Assistant-style overlays, logging, and tests. */
@ApiStatus.Experimental
@ExperimentalJewelApi
public interface ActionEventSource {
    public val invocations: SharedFlow<ActionInvocation>
}

@ApiStatus.Experimental
@ExperimentalJewelApi
public class MutableActionEventSource : ActionEventSource {
    private val _invocations = MutableSharedFlow<ActionInvocation>(extraBufferCapacity = 64)

    override val invocations: SharedFlow<ActionInvocation> = _invocations.asSharedFlow()

    /** Emits exactly one event per completed invocation; drops (never blocks) if nobody keeps up. */
    public fun emit(invocation: ActionInvocation) {
        _invocations.tryEmit(invocation)
    }
}

@ApiStatus.Experimental
@ExperimentalJewelApi
public enum class ActionDispatchRejection {
    Unregistered,
    NoFocusedBinding,
    Disabled,
    HostUnavailable,
}

@ApiStatus.Experimental
@ExperimentalJewelApi
public sealed interface ActionDispatchResult {
    public data object Dispatched : ActionDispatchResult

    public data class Rejected(val reason: ActionDispatchRejection) : ActionDispatchResult
}

/**
 * Requests normal host action execution. In standalone this resolves the focused Jewel binding; the IJPL
 * bridge routes through `ActionManager.tryToExecute` so platform update/enablement stays authoritative.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
public interface ActionInvoker {
    public fun invoke(
        action: JewelAction,
        trigger: ActionTrigger = ActionTrigger.Programmatic,
    ): ActionDispatchResult
}
