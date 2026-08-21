// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.intui.standalone.popup

import dev.sebastiano.spectre.core.ComposeAutomator
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.jetbrains.jewel.e2e.PopupAutomator

/**
 * Backs the shared popup scenarios with Spectre's in-process automator, against the standalone sample.
 *
 * The IDE lane backs the same scenarios with Spectre attached to a running IDE. Keeping both behind one interface is
 * what stops the two hosts drifting: a popup fix that only lands for one of them fails here.
 */
internal class SpectrePopupAutomator(
    private val automator: ComposeAutomator,
    private val timeout: Duration = 10.seconds,
    private val pollInterval: Duration = 200.milliseconds,
) : PopupAutomator {
    override suspend fun waitForNode(tag: String) {
        pollUntil("a node tagged '$tag' to appear") { isPresent(tag) }
    }

    override suspend fun waitUntilGone(tag: String) {
        pollUntil("a node tagged '$tag' to go away") { !isPresent(tag) }
    }

    override suspend fun isPresent(tag: String): Boolean {
        automator.refreshWindows()
        return automator.findByTestTag(tag).isNotEmpty()
    }

    override suspend fun click(tag: String) {
        automator.refreshWindows()
        val node = automator.findByTestTag(tag).firstOrNull() ?: error("Nothing tagged '$tag' to click")
        automator.click(node)
    }

    override suspend fun pressKey(keyCode: Int) {
        automator.pressKey(keyCode)
    }

    override suspend fun waitForIdle() {
        automator.waitForIdle()
    }

    /** Clicks a node by its accessibility description, which is how the showcase's view switcher is addressable. */
    suspend fun clickByContentDescription(description: String) {
        pollUntil("a node described as '$description' to appear") {
            automator.refreshWindows()
            automator.findByContentDescription(description).isNotEmpty()
        }
        automator.click(automator.findByContentDescription(description).first())
    }

    private suspend fun pollUntil(what: String, condition: suspend () -> Boolean) {
        var waited = Duration.ZERO
        while (waited < timeout) {
            if (condition()) return
            delay(pollInterval)
            waited += pollInterval
        }
        automator.refreshWindows()
        val tags = automator.allNodes().mapNotNull { it.testTag }.distinct().sorted()
        error("Timed out after $timeout waiting for $what. Visible tags: $tags")
    }
}
