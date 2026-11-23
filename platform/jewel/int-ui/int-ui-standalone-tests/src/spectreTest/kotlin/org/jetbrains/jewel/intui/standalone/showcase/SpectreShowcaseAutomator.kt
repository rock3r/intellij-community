// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.intui.standalone.showcase

import dev.sebastiano.spectre.core.AutomatorNode
import dev.sebastiano.spectre.core.ComposeAutomator
import org.jetbrains.jewel.e2e.ShowcaseAutomator

/**
 * Backs the shared popup scenarios with Spectre's in-process automator, against the standalone sample.
 *
 * The IDE lane backs the same scenarios with Spectre attached to a running IDE. Keeping both behind one interface is
 * what stops the two hosts drifting.
 */
internal class SpectreShowcaseAutomator(private val automator: ComposeAutomator) : ShowcaseAutomator {
    override suspend fun isPresent(tag: String): Boolean {
        automator.refreshWindows()
        return automator.findByTestTag(tag).isNotEmpty()
    }

    override suspend fun hasVisibleBounds(tag: String): Boolean {
        automator.refreshWindows()
        val bounds = automator.findByTestTag(tag).firstOrNull()?.boundsOnScreen ?: return false
        return bounds.width > MIN_CLIPPED_BOUNDS_PX && bounds.height > MIN_CLIPPED_BOUNDS_PX
    }

    override suspend fun visibleTags(): List<String> {
        automator.refreshWindows()
        return automator.allNodes().mapNotNull { it.testTag }.distinct().sorted()
    }

    override suspend fun click(tag: String) {
        automator.refreshWindows()
        val node = automator.findByTestTag(tag).firstOrNull() ?: error("Nothing tagged '$tag' to click")
        automator.click(node)
    }

    override suspend fun clickByText(text: String) {
        automator.refreshWindows()
        val node = automator.allNodes().lastOrNull { text in it.texts } ?: error("Nothing with text '$text' to click")
        automator.click(node)
    }

    override suspend fun scrollWheel(tag: String, wheelClicks: Int) {
        automator.refreshWindows()
        val node = automator.findByTestTag(tag).firstOrNull() ?: error("Nothing tagged '$tag' to scroll")
        automator.scrollWheel(node, wheelClicks)
    }

    override suspend fun pressKey(keyCode: Int, modifiers: Int) {
        automator.pressKey(keyCode, modifiers)
    }

    override suspend fun typeText(text: String) {
        automator.typeText(text)
    }

    override suspend fun textOf(tag: String): String? {
        automator.refreshWindows()
        val node = automator.findByTestTag(tag).firstOrNull() ?: return null
        return node.texts.joinToString(" ").ifEmpty { null }
    }

    override suspend fun editableTextOf(tag: String): String? {
        automator.refreshWindows()
        return automator.findByTestTag(tag).firstOrNull()?.editableText
    }

    override suspend fun isEnabled(tag: String): Boolean {
        automator.refreshWindows()
        val node = automator.findByTestTag(tag).firstOrNull() ?: return false
        return !findInteractiveTarget(node).isDisabled
    }

    private fun findInteractiveTarget(node: AutomatorNode): AutomatorNode {
        if (node.isDisabled || node.isClickable) return node
        val queue = ArrayDeque(node.children)
        while (queue.isNotEmpty()) {
            val child = queue.removeFirst()
            if (child.isClickable || child.isDisabled) return child
            queue.addAll(child.children)
        }
        return node
    }

    override suspend fun isSelected(tag: String): Boolean {
        automator.refreshWindows()
        val node = automator.findByTestTag(tag).firstOrNull() ?: return false
        return node.isSelected || findInteractiveTarget(node).isSelected
    }

    override suspend fun assertToggled(tag: String, expected: Boolean) {
        waitUntil("'$tag' toggled state to be $expected") { isSelected(tag) == expected }
    }

    override suspend fun waitForIdle() {
        automator.waitForIdle()
    }

    override suspend fun clickByContentDescription(description: String) {
        waitUntil("a node described as '$description' to appear") {
            automator.refreshWindows()
            automator.findByContentDescription(description).isNotEmpty()
        }
        waitUntil("a node described as '$description' to have visible bounds") {
            automator.refreshWindows()
            val target = automator.findByContentDescription(description).firstOrNull() ?: return@waitUntil false
            val targetBounds = target.boundsOnScreen
            if (targetBounds.width > MIN_CLIPPED_BOUNDS_PX && targetBounds.height > MIN_CLIPPED_BOUNDS_PX) {
                return@waitUntil true
            }

            val nodes = automator.allNodes()
            val targetIndex = nodes.indexOfFirst { it.key == target.key }
            val visibleToolbarNode =
                nodes.firstOrNull {
                    val bounds = it.boundsOnScreen
                    it.contentDescription?.startsWith("Show ") == true &&
                        bounds.width > MIN_CLIPPED_BOUNDS_PX &&
                        bounds.height > MIN_CLIPPED_BOUNDS_PX
                } ?: return@waitUntil false
            val visibleIndex = nodes.indexOfFirst { it.key == visibleToolbarNode.key }
            val wheelClicks = if (targetIndex >= visibleIndex) 5 else -5
            automator.scrollWheel(visibleToolbarNode, wheelClicks)
            automator.waitForIdle()
            automator.refreshWindows()
            val updatedBounds = automator.findByContentDescription(description).firstOrNull()?.boundsOnScreen
            updatedBounds != null &&
                updatedBounds.width > MIN_CLIPPED_BOUNDS_PX &&
                updatedBounds.height > MIN_CLIPPED_BOUNDS_PX
        }
        automator.click(automator.findByContentDescription(description).first())
    }
}

// Spectre clamps empty clipped bounds to 1x1 px in composeBoundsToAwtRectangle; require more than that
// so partially or fully clipped nodes are scrolled into view before clicking.
private const val MIN_CLIPPED_BOUNDS_PX = 4
