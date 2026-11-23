// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(ExperimentalSpectreAgentApi::class)

package org.jetbrains.jewel.bridge.e2e

import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.RectDto
import org.jetbrains.jewel.e2e.ShowcaseAutomator

/**
 * Backs the shared popup scenarios with Spectre attached to a running IDE.
 *
 * Keys are not Spectre's to press here. Since 0.6.0 the attach path drives synthetic AWT input, which hands a key event
 * straight to the Compose host's peer and never enters `IdeEventQueue`. That is where an open `JBPopup` gets first
 * refusal of the key, so the bridge renderer's Escape handling, the very thing the JEWEL-1396 scenario exists to
 * observe, would never run; the ComboBox would close itself and the lane would pass with the renderer broken. The key
 * therefore comes in at the OS level through [pressOsKey] and takes the same route as a user's.
 */
internal class AttachedShowcaseAutomator(
    private val attached: AttachedAutomator,
    private val pressOsKey: (keyCode: Int, modifiers: Int) -> Unit,
    private val typeOsText: (text: String) -> Unit,
) : ShowcaseAutomator {
    override suspend fun isPresent(tag: String): Boolean = attached.findByTestTag(tag).isNotEmpty()

    override suspend fun hasVisibleBounds(tag: String): Boolean {
        val bounds = attached.findByTestTag(tag).firstOrNull()?.bounds ?: return false
        return bounds.width > MIN_CLIPPED_BOUNDS_PX && bounds.height > MIN_CLIPPED_BOUNDS_PX
    }

    override suspend fun visibleTags(): List<String> = attached.allNodes().mapNotNull { it.testTag }.distinct().sorted()

    override suspend fun click(tag: String) {
        val node = attached.findByTestTag(tag).firstOrNull() ?: error("Nothing tagged '$tag' to click")
        attached.click(node)
    }

    override suspend fun clickByText(text: String) {
        val node = attached.allNodes().lastOrNull { text in it.texts } ?: error("Nothing with text '$text' to click")
        attached.click(node)
    }

    override suspend fun scrollWheel(tag: String, wheelClicks: Int) {
        val node = attached.findByTestTag(tag).firstOrNull() ?: error("Nothing tagged '$tag' to scroll")
        attached.scrollWheel(node.key, wheelClicks)
    }

    override suspend fun pressKey(keyCode: Int, modifiers: Int) {
        pressOsKey(keyCode, modifiers)
    }

    override suspend fun typeText(text: String) {
        typeOsText(text)
    }

    override suspend fun textOf(tag: String): String? {
        val node = attached.findByTestTag(tag).firstOrNull() ?: return null
        return node.texts.joinToString(" ").ifEmpty { null }
    }

    override suspend fun editableTextOf(tag: String): String? =
        attached.findByTestTag(tag).firstOrNull()?.editableText

    override suspend fun isEnabled(tag: String): Boolean {
        val node = attached.findByTestTag(tag).firstOrNull() ?: return false
        if (node.isDisabled) return false
        if (node.role != null) return true
        val ownerPrefix = node.key.substringBeforeLast(':')
        val descendantDisabled =
            attached.allNodes().any { other ->
                other.key != node.key &&
                    other.key.startsWith("$ownerPrefix:") &&
                    other.isDisabled &&
                    other.bounds.isInside(node.bounds)
            }
        return !descendantDisabled
    }

    override suspend fun isSelected(tag: String): Boolean =
        attached.findByTestTag(tag).firstOrNull()?.isSelected == true

    override suspend fun assertToggled(tag: String, expected: Boolean) {
        waitUntil("'$tag' toggled state to be $expected") { isSelected(tag) == expected }
    }

    override suspend fun waitForIdle() {
        attached.waitForIdle()
    }

    override suspend fun clickByContentDescription(description: String) {
        waitUntil("a node described as '$description' to appear") {
            attached.findByContentDescription(description).isNotEmpty()
        }
        waitUntil("a node described as '$description' to have visible bounds") {
            val target = attached.findByContentDescription(description).firstOrNull() ?: return@waitUntil false
            if (target.bounds.width > MIN_CLIPPED_BOUNDS_PX && target.bounds.height > MIN_CLIPPED_BOUNDS_PX) {
                return@waitUntil true
            }

            val nodes = attached.allNodes()
            val targetIndex = nodes.indexOfFirst { it.key == target.key }
            val visibleToolbarNode =
                nodes.firstOrNull {
                    it.contentDescription?.startsWith("Show ") == true &&
                        it.bounds.width > MIN_CLIPPED_BOUNDS_PX &&
                        it.bounds.height > MIN_CLIPPED_BOUNDS_PX
                } ?: return@waitUntil false
            val visibleIndex = nodes.indexOfFirst { it.key == visibleToolbarNode.key }
            val wheelClicks = if (targetIndex >= visibleIndex) 5 else -5
            attached.scrollWheel(visibleToolbarNode.key, wheelClicks)
            attached.waitForIdle()
            val updated = attached.findByContentDescription(description).firstOrNull()
            updated != null &&
                updated.bounds.width > MIN_CLIPPED_BOUNDS_PX &&
                updated.bounds.height > MIN_CLIPPED_BOUNDS_PX
        }
        attached.click(attached.findByContentDescription(description).first())
    }
}

// Spectre clamps empty clipped bounds to 1x1 px in composeBoundsToAwtRectangle; require more than that
// so partially or fully clipped nodes are scrolled into view before clicking.
private const val MIN_CLIPPED_BOUNDS_PX = 4

private fun RectDto.isInside(container: RectDto): Boolean =
    width > 0 &&
        height > 0 &&
        x >= container.x &&
        y >= container.y &&
        x + width <= container.x + container.width &&
        y + height <= container.y + container.height
