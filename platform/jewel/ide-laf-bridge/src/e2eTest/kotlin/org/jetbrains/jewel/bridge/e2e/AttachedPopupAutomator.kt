// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(ExperimentalSpectreAgentApi::class)

package org.jetbrains.jewel.bridge.e2e

import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.jetbrains.jewel.e2e.PopupAutomator

/**
 * Backs the shared popup scenarios with Spectre attached to a running IDE.
 *
 * [AttachedAutomator]'s calls are blocking and go over the agent's IPC socket, so the waiting helpers are written here
 * rather than borrowed from the in-process automator, which has its own suspending ones.
 */
internal class AttachedPopupAutomator(
    private val attached: AttachedAutomator,
    private val timeout: Duration = 10.seconds,
    private val pollInterval: Duration = 200.milliseconds,
) : PopupAutomator {
    override suspend fun waitForNode(tag: String) {
        pollUntil("a node tagged '$tag' to appear") { isPresent(tag) }
    }

    override suspend fun waitUntilGone(tag: String) {
        pollUntil("a node tagged '$tag' to go away") { !isPresent(tag) }
    }

    override suspend fun isPresent(tag: String): Boolean = attached.findByTestTag(tag).isNotEmpty()

    override suspend fun click(tag: String) {
        val node = attached.findByTestTag(tag).firstOrNull() ?: error("Nothing tagged '$tag' to click")
        attached.click(node)
    }

    override suspend fun pressKey(keyCode: Int) {
        attached.pressKey(keyCode, 0)
    }

    override suspend fun waitForIdle() {
        attached.waitForIdle()
    }

    /** Clicks a node by its accessibility description, which is how the showcase's view switcher is addressable. */
    suspend fun clickByContentDescription(description: String) {
        pollUntil("a node described as '$description' to appear") {
            attached.findByContentDescription(description).isNotEmpty()
        }
        val node = attached.findByContentDescription(description).first()
        attached.click(node)
    }

    private suspend fun pollUntil(what: String, condition: suspend () -> Boolean) {
        var waited = Duration.ZERO
        while (waited < timeout) {
            if (condition()) return
            delay(pollInterval)
            waited += pollInterval
        }
        // Naming what *is* on screen turns "timed out" into something diagnosable: a wrong tag, a view that never
        // switched, and a surface the automator cannot see all look identical without it.
        error("Timed out after $timeout waiting for $what. Visible tags: ${visibleTags()}")
    }

    /** Every test tag the automator can currently see, for failure messages. */
    suspend fun visibleTags(): List<String> =
        runCatching { attached.allNodes().mapNotNull { it.testTag }.distinct().sorted() }
            .getOrElse { listOf("<could not read the tree: ${it.message}>") }
}
