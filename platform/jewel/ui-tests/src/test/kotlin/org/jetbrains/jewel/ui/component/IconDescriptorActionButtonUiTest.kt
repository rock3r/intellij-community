// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.intellij.platform.icons.IconDescriptor
import com.intellij.platform.icons.iconDescriptor
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.jewel.foundation.shortcut.ActionGroupPresentation
import org.jetbrains.jewel.foundation.shortcut.DefaultJewelActionRegistry
import org.jetbrains.jewel.foundation.shortcut.InMemoryJewelKeymap
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelActionDefinition
import org.jetbrains.jewel.foundation.shortcut.JewelActionGroupId
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.jetbrains.jewel.foundation.shortcut.JewelKeySequence
import org.jetbrains.jewel.foundation.shortcut.JewelKeyStroke
import org.jetbrains.jewel.foundation.shortcut.JewelMenuEntry
import org.jetbrains.jewel.foundation.shortcut.JewelShortcutHostState
import org.jetbrains.jewel.foundation.shortcut.ProvideJewelShortcutHost
import org.jetbrains.jewel.foundation.shortcut.StaticJewelActionGroup
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.icon.iconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.junit.Rule
import org.junit.Test

/**
 * The icon-descriptor overloads of the icon action buttons. Descriptors are the platform's cross-frontend icon model,
 * so these exist to let one icon value drive both a Swing and a Compose control; the tests assert they behave exactly
 * like their [org.jetbrains.jewel.ui.icon.IconKey] counterparts — same semantics, same enablement, same toggling.
 *
 * Descriptors are built lazily inside the composition: creating one goes through the `IconManager` the theme installs,
 * so building them at construction time would run before there is one.
 */
class IconDescriptorActionButtonUiTest {
    @get:Rule val rule = createComposeRule()

    private fun softWrap(): IconDescriptor = iconDescriptor { iconKey(AllIconsKeys.Actions.ToggleSoftWrap) }

    /**
     * These components hand the caller's modifier straight to the underlying icon button, which applies it before its
     * own `clickable`, so the tag and the interaction semantics land on the same node.
     */
    private fun control(tag: String) = rule.onNodeWithTag(tag)

    @Test
    fun `icon action button renders the descriptor and clicks`() {
        var clicks = 0
        rule.setContent {
            IntUiTheme {
                IconActionButton(
                    icon = softWrap(),
                    contentDescription = "Soft wrap",
                    onClick = { clicks++ },
                    modifier = Modifier.testTag("button"),
                )
            }
        }

        rule.onNodeWithContentDescription("Soft wrap").assertExists().assertIsDisplayed()
        control("button").assertHasClickAction().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `disabled icon action button does not invoke`() {
        var clicks = 0
        rule.setContent {
            IntUiTheme {
                IconActionButton(
                    icon = softWrap(),
                    contentDescription = "Soft wrap",
                    onClick = { clicks++ },
                    enabled = false,
                    modifier = Modifier.testTag("button"),
                )
            }
        }

        control("button").assertIsNotEnabled().performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun `tooltip overload still renders the button`() {
        rule.setContent {
            IntUiTheme {
                IconActionButton(
                    icon = softWrap(),
                    contentDescription = "Soft wrap",
                    onClick = {},
                    modifier = Modifier.testTag("button"),
                    tooltip = { Text("Toggle soft wrap") },
                )
            }
        }

        rule.onNodeWithContentDescription("Soft wrap").assertExists().assertIsDisplayed()
        control("button").assertHasClickAction()
    }

    @Test
    fun `toggleable icon action button reports and flips its value`() {
        var value = false
        rule.setContent {
            IntUiTheme {
                ToggleableIconActionButton(
                    icon = softWrap(),
                    contentDescription = "Soft wrap",
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.testTag("toggle"),
                )
            }
        }

        control("toggle").assertIsOff().performClick()
        rule.waitForIdle()
        assertTrue(value)
    }

    @Test
    fun `toggleable icon action button reflects an externally set value`() {
        rule.setContent {
            IntUiTheme {
                ToggleableIconActionButton(
                    icon = softWrap(),
                    contentDescription = "Soft wrap",
                    value = true,
                    onValueChange = {},
                    modifier = Modifier.testTag("toggle"),
                )
            }
        }

        control("toggle").assertIsOn()
    }

    @Test
    fun `selectable icon action button reports its selection`() {
        rule.setContent {
            IntUiTheme {
                SelectableIconActionButton(
                    icon = softWrap(),
                    contentDescription = "Soft wrap",
                    selected = true,
                    onClick = {},
                    modifier = Modifier.testTag("selectable"),
                )
            }
        }

        control("selectable").assertIsSelected()
    }

    @Test
    fun `selectable icon action button with tooltip clicks`() {
        var clicks = 0
        rule.setContent {
            IntUiTheme {
                SelectableIconActionButton(
                    icon = softWrap(),
                    contentDescription = "Soft wrap",
                    selected = false,
                    onClick = { clicks++ },
                    modifier = Modifier.testTag("selectable"),
                    tooltip = { Text("Toggle soft wrap") },
                )
            }
        }

        control("selectable").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `split action button exposes Role Button on primary and chevron`() {
        val action = JewelAction(JewelActionId("test.split.primary"), "Run")
        val group =
            StaticJewelActionGroup(
                JewelActionGroupId("test.split.menu"),
                ActionGroupPresentation(text = "More", popup = true),
                listOf(JewelMenuEntry.Action(action)),
            )
        rule.setContent {
            IntUiTheme { SplitActionButton(primary = action, menuGroup = group, modifier = Modifier.testTag("split")) }
        }

        val roleButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        rule.onNodeWithTag("Jewel.SplitActionButton.Primary").assert(roleButton)
        rule.onNodeWithTag("Jewel.SplitActionButton.Chevron").assert(roleButton)
    }

    @Test
    fun `action menu preserves repeated chord modifier hint without deduplication`() {
        val action = JewelAction(JewelActionId("test.menu.chord"), "Chord Action")
        val chord =
            JewelKeySequence(JewelKeyStroke(Key.K, shift = true), JewelKeyStroke(Key.C, ctrl = true, shift = true))
        val registry = DefaultJewelActionRegistry()
        registry.register(JewelActionDefinition(action, listOf(chord)))
        val keymap = InMemoryJewelKeymap.fromDefaults("chord-test", registry)
        val host = JewelShortcutHostState(registry) { keymap }
        val group =
            StaticJewelActionGroup(
                JewelActionGroupId("test.menu.group"),
                ActionGroupPresentation(text = "Actions", popup = true),
                listOf(JewelMenuEntry.Action(action)),
            )

        rule.setContent {
            IntUiTheme {
                ProvideJewelShortcutHost(host) {
                    Box(host.resolverRootModifier) { ActionMenu(group = group, onDismissRequest = {}) }
                }
            }
        }

        rule.onNodeWithText(chord.displayText()).assertExists()
    }
}
