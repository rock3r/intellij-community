// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the
// Apache 2.0 license.
package org.jetbrains.jewel.samples.showcase.views

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import org.jetbrains.jewel.foundation.shortcut.DefaultJewelActionRegistry
import org.jetbrains.jewel.foundation.shortcut.InMemoryJewelKeymap
import org.jetbrains.jewel.foundation.shortcut.JewelShortcutHostState
import org.jetbrains.jewel.foundation.shortcut.ProvideJewelShortcutHost
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.samples.showcase.LocalShowcaseActionRegistry
import org.jetbrains.jewel.samples.showcase.LocalShowcaseKeymap
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents
import org.junit.Rule
import org.junit.Test

/**
 * The action components are bound to actions, never to handlers: they render the focused binding's sampled presentation
 * and invoke through the host. These tests drive the real Action Components page and assert that contract — enablement,
 * visibility, toggle state, pointer/keyboard agreement, and group structure.
 *
 * Focus is the load-bearing precondition: presentation resolves against the *focused* binding, so each test focuses the
 * binding surface first. Without focus every control correctly renders unavailable, which the disabled-action test
 * relies on.
 */
public class ActionComponentsUiTest {
    @get:Rule public val rule: ComposeContentTestRule = createComposeRule()

    /**
     * The control that actually carries the interaction semantics for a tagged action component. The tag rides the
     * caller's modifier, which lands on the component's outer box, while the click/enablement semantics live on the
     * clickable node the underlying icon button creates — so assertions must target the descendant, not the tag.
     */
    private fun control(tag: String): SemanticsNodeInteraction =
        rule.onNode(hasAnyAncestor(hasTestTag(tag)) and hasClickAction())

    /** Composes the page and focuses the surface that declares the bindings. */
    private fun setContentFocused() {
        rule.setContent {
            IntUiTheme {
                val registry = remember {
                    DefaultJewelActionRegistry().also { registry ->
                        ShowcaseActionComponents.definitions(useMacModifiers = false).forEach(registry::register)
                    }
                }
                val keymap = remember { InMemoryJewelKeymap.fromDefaults("test", registry) }
                val host = remember { JewelShortcutHostState(registry) { keymap } }
                ProvideJewelShortcutHost(host) {
                    Box(host.resolverRootModifier) {
                        CompositionLocalProvider(
                            LocalShowcaseKeymap provides keymap,
                            LocalShowcaseActionRegistry provides registry,
                        ) {
                            ActionComponentsView()
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("ActionComponents.Surface").requestFocus()
        rule.waitForIdle()
    }

    @Test
    public fun `a bound action renders enabled and invokes through the host on click`() {
        setContentFocused()

        // The compact counter row reads "Fired — Save 0 · Refresh 0 · …"; assert the Save tally within it.
        rule.onNodeWithText("Save 0", substring = true).assertExists()
        control("ActionComponents.Save").assertIsEnabled().assertHasClickAction().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Save 1", substring = true).assertExists()
    }

    @Test
    public fun `an action with no binding anywhere renders disabled`() {
        setContentFocused()

        // 'Unavailable' is registered but never bound: the NoFocusedBinding presentation row.
        control("ActionComponents.Unavailable").assertIsNotEnabled()
    }

    @Test
    public fun `disabling the binding disables the button, keyboard and pointer agreeing`() {
        setContentFocused()

        control("ActionComponents.Delete").assertIsEnabled()

        rule.onNodeWithTag("ActionComponents.DeleteToggle").performClick()
        rule.waitForIdle()

        control("ActionComponents.Delete").assertIsNotEnabled()
    }

    @Test
    public fun `a hidden action is not emitted unless visibility is ignored`() {
        setContentFocused()

        // Archive is bound but its presentation override sets visible = false.
        rule.onNodeWithTag("ActionComponents.ArchiveRespectingVisibility").assertDoesNotExist()
        rule.onNodeWithTag("ActionComponents.ArchiveIgnoringVisibility").assertExists()
    }

    @Test
    public fun `toggle state comes from the sampled presentation, not from the control`() {
        setContentFocused()

        rule.onNodeWithText("Word wrap: false", substring = true).assertExists()

        rule.onNodeWithTag("ActionComponents.WordWrap").assertIsEnabled().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Word wrap: true", substring = true).assertExists()
    }

    @Test
    public fun `the toolbar flattens inline subgroups and renders popup subgroups as menu buttons`() {
        setContentFocused()

        // The inline 'Editing' subgroup is flattened into the toolbar row: its toggle renders inside the toolbar.
        // Toolbar controls render as icons, so they are identified by the content description the icon carries —
        // the action's text — rather than by visible label text.
        rule
            .onNode(
                hasTestTag("ActionComponents.Toolbar") and hasAnyDescendant(hasContentDescription("Show whitespace"))
            )
            .assertExists()
        // The popup 'More' subgroup becomes a group button inside the same row instead of being flattened.
        rule
            .onNode(hasTestTag("ActionComponents.Toolbar") and hasAnyDescendant(hasContentDescription("More")))
            .assertExists()
    }

    @Test
    public fun `the split button exposes a primary action and a menu affordance`() {
        setContentFocused()

        // The split button hosts a primary ActionButton (the tagged Save) plus a chevron menu affordance.
        rule.onNode(hasTestTag("ActionComponents.SplitButton") and hasAnyDescendant(hasClickAction())).assertExists()
        // The page scrolls, so the group button need only exist in the tree, not be on screen.
        rule.onNode(hasTestTag("ActionComponents.MenuButton") and hasAnyDescendant(hasClickAction())).assertExists()
    }

    @Test
    public fun `the keymap panel lists the registered actions for rebinding`() {
        setContentFocused()

        // The keymap editor lives behind the "Keybindings" tab now.
        rule.onNodeWithText("Keybindings").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("ActionComponents.KeymapPanel").assertExists()
    }

    @Test
    public fun `an action opting into toolbar text renders its label, not an icon`() {
        setContentFocused()
        rule.onNodeWithTag("ActionComponents.TextAction").assertExists()
        rule.onNode(hasAnyAncestor(hasTestTag("ActionComponents.TextAction")) and hasText("Commit")).assertExists()
    }

    @Test
    public fun `each toggle reflects its own action, not a shared state`() {
        setContentFocused()
        rule.onNodeWithText("Word wrap: false", substring = true).assertExists()
        rule.onNodeWithText("Show whitespace: false", substring = true).assertExists()

        rule.onNodeWithTag("ActionComponents.WordWrap").performClick()
        rule.waitForIdle()

        // Only word wrap flipped; the neighbouring toggle must be untouched.
        rule.onNodeWithText("Word wrap: true", substring = true).assertExists()
        rule.onNodeWithText("Show whitespace: false", substring = true).assertExists()
        rule.onNodeWithTag("ActionComponents.WordWrap").assertIsOn()
        rule.onNodeWithTag("ActionComponents.ShowWhitespace").assertIsOff()
    }
}
