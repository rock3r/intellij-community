// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.e2e

/**
 * The slice of UI automation the popup scenarios need, so that one set of scenarios can run against both hosts Jewel
 * supports.
 *
 * Standalone backs this with Spectre's in-process automator against the sample app; the IDE backs it with Spectre
 * attached over the agent's IPC against DevKit's components showcase. The two automators are not source-compatible —
 * different node types, different method shapes, one suspending and one blocking — so without something like this the
 * two lanes would drift, and a bug fixed for one host could quietly persist in the other.
 *
 * Deliberately tiny, and deliberately free of both Spectre and IntelliJ Platform types: the standalone lane asserts
 * that no platform class reaches its runtime closure.
 */
public interface PopupAutomator {
    /** Waits until a node with [tag] exists, failing if it does not appear in time. */
    public suspend fun waitForNode(tag: String)

    /** Waits until nothing with [tag] is on screen, failing if it is still there in time. */
    public suspend fun waitUntilGone(tag: String)

    /** Whether anything with [tag] is on screen right now, across every surface the host knows about. */
    public suspend fun isPresent(tag: String): Boolean

    /**
     * Clicks the node with [tag], leaving the pointer where it landed.
     *
     * Several scenarios depend on that: hover state is what makes a ComboBox suppress its pointer dismissal, and moving
     * the pointer away between actions would quietly change what is being tested.
     */
    public suspend fun click(tag: String)

    /** Presses and releases [keyCode], an [java.awt.event.KeyEvent] `VK_` constant. */
    public suspend fun pressKey(keyCode: Int)

    /** Waits for the UI to settle, so a check does not race a recomposition or an animation. */
    public suspend fun waitForIdle()
}
