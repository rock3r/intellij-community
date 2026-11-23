// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.e2e

import java.awt.event.KeyEvent

/**
 * Shared action-bound component scenarios that must behave identically in the standalone sample and the IDE bridge.
 */
public object ActionComponentScenarios {
    /** Opens the Action Components showcase view and runs each scenario in isolation. */
    public suspend fun runAll(automator: ShowcaseAutomator, failures: MutableList<String>) {
        automator.clickByContentDescription("Show Action Components")
        automator.waitForNode(ShowcaseE2eTags.ACTION_COMPONENTS_SAVE)
        if (!automator.isEnabled(ShowcaseE2eTags.ACTION_COMPONENTS_SAVE)) {
            automator.click(ShowcaseE2eTags.ACTION_COMPONENTS_COUNTERS)
        }
        automator.waitUntil("initial Action Components surface focus enables Save") {
            automator.isEnabled(ShowcaseE2eTags.ACTION_COMPONENTS_SAVE)
        }
        automator.waitForIdle()
        automator.runIsolated(
            failures,
            "ActionButton invokes via host and tracks binding enablement",
            ::restoreBaseline,
            ::actionButtonInvokesAndTracksEnablement,
        )
        automator.runIsolated(
            failures,
            "ToggleActionButton reflects sampled selected state",
            ::restoreBaseline,
            ::toggleActionButtonReflectsSelectedState,
        )
        automator.runIsolated(
            failures,
            "ActionGroupButton opens ActionMenu, honors MenuDismissPolicy, and closes on Escape",
            ::restorePopupAndScroll,
            ::actionGroupButtonPopupAndMenuDismissPolicy,
        )
        automator.runIsolated(
            failures,
            "SplitActionButton invokes primary action and opens/dismisses menu",
            ::restorePopupAndScroll,
            ::splitActionButtonInvokesPrimaryAndOpensMenu,
        )
        automator.runIsolated(
            failures,
            "Focus loss disables surface controls and surface click recovers",
            ::restoreBaseline,
            ::focusLossDisablesControlsAndSurfaceClickRecovers,
        )
    }

    private suspend fun scrollToVisible(automator: ShowcaseAutomator, tag: String, wheelClicksPerStep: Int) {
        var attempts = 0
        while (!automator.hasVisibleBounds(tag) && attempts < 12) {
            automator.scrollWheel(ShowcaseE2eTags.ACTION_COMPONENTS_SCROLL_AREA, wheelClicksPerStep)
            automator.waitForIdle()
            attempts++
        }
        if (!automator.hasVisibleBounds(tag)) {
            error("Timed out scrolling '$tag' into visible bounds")
        }
    }

    private suspend fun restoreBaseline(automator: ShowcaseAutomator) {
        if (automator.isPresent(ShowcaseE2eTags.ACTION_MENU_POPUP)) {
            automator.pressKey(KeyEvent.VK_ESCAPE)
            automator.waitUntilGone(ShowcaseE2eTags.ACTION_MENU_POPUP)
        }
        scrollToVisible(automator, ShowcaseE2eTags.ACTION_COMPONENTS_SAVE, wheelClicksPerStep = -6)
        if (!automator.isEnabled(ShowcaseE2eTags.ACTION_COMPONENTS_SAVE)) {
            automator.click(ShowcaseE2eTags.ACTION_COMPONENTS_COUNTERS)
            automator.waitForIdle()
        }
        if (!automator.isSelected(ShowcaseE2eTags.ACTION_COMPONENTS_DELETE_TOGGLE)) {
            automator.click(ShowcaseE2eTags.ACTION_COMPONENTS_DELETE_TOGGLE)
            automator.waitForIdle()
        }
    }

    private suspend fun restorePopupAndScroll(automator: ShowcaseAutomator) {
        restoreBaseline(automator)
    }

    public suspend fun actionButtonInvokesAndTracksEnablement(automator: ShowcaseAutomator): String? {
        if (automator.isEnabled(ShowcaseE2eTags.ACTION_COMPONENTS_UNAVAILABLE)) {
            return "expected unbound ActionButton 'Unavailable' to render disabled"
        }
        if (!automator.isEnabled(ShowcaseE2eTags.ACTION_COMPONENTS_DELETE)) {
            return "expected 'Delete' ActionButton to start enabled"
        }

        val savesBefore = counterValue(automator, "Save")
        automator.click(ShowcaseE2eTags.ACTION_COMPONENTS_SAVE)
        automator.waitUntil("Save fired counter to reach ${savesBefore + 1}") {
            counterValue(automator, "Save") == savesBefore + 1
        }

        // Disable the Delete binding via checkbox and verify the ActionButton disables in sync.
        automator.click(ShowcaseE2eTags.ACTION_COMPONENTS_DELETE_TOGGLE)
        automator.waitUntil("'Delete' ActionButton to become disabled") {
            !automator.isEnabled(ShowcaseE2eTags.ACTION_COMPONENTS_DELETE)
        }

        // Re-enable the Delete binding and verify the ActionButton recovers.
        automator.click(ShowcaseE2eTags.ACTION_COMPONENTS_DELETE_TOGGLE)
        automator.waitUntil("'Delete' ActionButton to become enabled again") {
            automator.isEnabled(ShowcaseE2eTags.ACTION_COMPONENTS_DELETE)
        }
        return null
    }

    public suspend fun toggleActionButtonReflectsSelectedState(automator: ShowcaseAutomator): String? {
        scrollToVisible(automator, ShowcaseE2eTags.ACTION_COMPONENTS_WORD_WRAP, wheelClicksPerStep = 4)
        val initiallyWrapped =
            automator.textOf(ShowcaseE2eTags.ACTION_COMPONENTS_TOGGLE_SUMMARY)?.contains("Word wrap: true") == true
        automator.assertToggled(ShowcaseE2eTags.ACTION_COMPONENTS_WORD_WRAP, initiallyWrapped)

        automator.click(ShowcaseE2eTags.ACTION_COMPONENTS_WORD_WRAP)
        val expectedAfterClick = "Word wrap: ${!initiallyWrapped}"
        automator.waitUntil("Word wrap state to flip to '${!initiallyWrapped}'") {
            automator.textOf(ShowcaseE2eTags.ACTION_COMPONENTS_TOGGLE_SUMMARY)?.contains(expectedAfterClick) == true
        }
        automator.assertToggled(ShowcaseE2eTags.ACTION_COMPONENTS_WORD_WRAP, !initiallyWrapped)

        // Toggle back to restore initial state.
        automator.click(ShowcaseE2eTags.ACTION_COMPONENTS_WORD_WRAP)
        val expectedRestored = "Word wrap: $initiallyWrapped"
        automator.waitUntil("Word wrap state to return to '$initiallyWrapped'") {
            automator.textOf(ShowcaseE2eTags.ACTION_COMPONENTS_TOGGLE_SUMMARY)?.contains(expectedRestored) == true
        }
        automator.assertToggled(ShowcaseE2eTags.ACTION_COMPONENTS_WORD_WRAP, initiallyWrapped)
        return null
    }

    public suspend fun actionGroupButtonPopupAndMenuDismissPolicy(automator: ShowcaseAutomator): String? {
        scrollToVisible(automator, ShowcaseE2eTags.ACTION_COMPONENTS_MENU_BUTTON, wheelClicksPerStep = 4)
        val initiallyWrapped =
            automator.textOf(ShowcaseE2eTags.ACTION_COMPONENTS_TOGGLE_SUMMARY)?.contains("Word wrap: true") == true

        // Default keepPopupsForToggles = false: clicking a toggle item flips it and dismisses the menu.
        automator.click(ShowcaseE2eTags.ACTION_COMPONENTS_MENU_BUTTON)
        automator.waitForNode(ShowcaseE2eTags.ACTION_MENU_POPUP)
        automator.clickByText("Word wrap")
        automator.waitUntilGone(ShowcaseE2eTags.ACTION_MENU_POPUP)
        automator.assertToggled(ShowcaseE2eTags.ACTION_COMPONENTS_WORD_WRAP, !initiallyWrapped)

        // keepPopupsForToggles = true: clicking a toggle keeps the menu open; clicking a command dismisses it.
        automator.click(ShowcaseE2eTags.ACTION_COMPONENTS_MENU_BUTTON_KEEP_OPEN)
        automator.waitForNode(ShowcaseE2eTags.ACTION_MENU_POPUP)
        automator.clickByText("Word wrap")
        automator.waitForIdle()
        automator.assertToggled(ShowcaseE2eTags.ACTION_COMPONENTS_WORD_WRAP, initiallyWrapped)
        if (!automator.isPresent(ShowcaseE2eTags.ACTION_MENU_POPUP)) {
            return "expected keepPopupsForToggles=true menu to stay open after toggle activation"
        }

        val refreshesBefore = counterValue(automator, "Refresh")
        automator.clickByText("Refresh")
        automator.waitUntilGone(ShowcaseE2eTags.ACTION_MENU_POPUP)
        automator.waitUntil("Refresh counter to reach ${refreshesBefore + 1}") {
            counterValue(automator, "Refresh") == refreshesBefore + 1
        }

        // Escape dismisses an open ActionMenu cleanly.
        automator.click(ShowcaseE2eTags.ACTION_COMPONENTS_MENU_BUTTON)
        automator.waitForNode(ShowcaseE2eTags.ACTION_MENU_POPUP)
        automator.waitForIdle()
        automator.pressKey(KeyEvent.VK_ESCAPE)
        automator.waitUntilGone(ShowcaseE2eTags.ACTION_MENU_POPUP)
        return null
    }

    public suspend fun splitActionButtonInvokesPrimaryAndOpensMenu(automator: ShowcaseAutomator): String? {
        val savesBefore = counterValue(automator, "Save")
        scrollToVisible(automator, ShowcaseE2eTags.SPLIT_BUTTON_PRIMARY, wheelClicksPerStep = 4)

        automator.click(ShowcaseE2eTags.SPLIT_BUTTON_PRIMARY)
        automator.waitUntil("Save fired counter to reach ${savesBefore + 1}") {
            counterValue(automator, "Save") == savesBefore + 1
        }

        automator.click(ShowcaseE2eTags.SPLIT_BUTTON_CHEVRON)
        automator.waitForNode(ShowcaseE2eTags.ACTION_MENU_POPUP)
        automator.waitForIdle()
        automator.pressKey(KeyEvent.VK_ESCAPE)
        automator.waitUntilGone(ShowcaseE2eTags.ACTION_MENU_POPUP)
        return null
    }

    public suspend fun focusLossDisablesControlsAndSurfaceClickRecovers(automator: ShowcaseAutomator): String? {
        scrollToVisible(automator, ShowcaseE2eTags.ACTION_COMPONENTS_SAVE, wheelClicksPerStep = -6)
        if (!automator.isEnabled(ShowcaseE2eTags.ACTION_COMPONENTS_SAVE)) {
            return "expected Save ActionButton to be enabled before focus loss"
        }

        // Clicking the right-hand log rail moves focus out of the binding surface.
        automator.click(ShowcaseE2eTags.ACTION_COMPONENTS_LOG)
        automator.waitUntil("'Save' ActionButton to disable when focus leaves surface") {
            !automator.isEnabled(ShowcaseE2eTags.ACTION_COMPONENTS_SAVE)
        }

        // Clicking an unconsumed node inside the binding surface re-focuses it.
        automator.click(ShowcaseE2eTags.ACTION_COMPONENTS_COUNTERS)
        automator.waitUntil("'Save' ActionButton to recover enablement after clicking surface") {
            automator.isEnabled(ShowcaseE2eTags.ACTION_COMPONENTS_SAVE)
        }
        return null
    }

    private suspend fun counterValue(automator: ShowcaseAutomator, label: String): Int {
        val text = automator.textOf(ShowcaseE2eTags.ACTION_COMPONENTS_COUNTERS).orEmpty()
        val match = Regex("""\b${Regex.escape(label)} (\d+)""").find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
