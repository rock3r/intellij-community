// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the
// Apache 2.0 license.
package org.jetbrains.jewel.samples.showcase.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.shortcut.ActionInvocation
import org.jetbrains.jewel.foundation.shortcut.ActionTrigger
import org.jetbrains.jewel.foundation.shortcut.JewelAction
import org.jetbrains.jewel.foundation.shortcut.JewelKeySequence
import org.jetbrains.jewel.foundation.shortcut.JewelKeyStroke
import org.jetbrains.jewel.foundation.shortcut.JewelShortcutHostState
import org.jetbrains.jewel.foundation.shortcut.LocalJewelShortcutHost
import org.jetbrains.jewel.foundation.shortcut.claimShortcut
import org.jetbrains.jewel.foundation.shortcut.shortcut
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.samples.showcase.ShowcaseShortcuts.DemoPing
import org.jetbrains.jewel.samples.showcase.ShowcaseShortcuts.DemoReformat
import org.jetbrains.jewel.samples.showcase.ShowcaseShortcuts.DemoSelectAll
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.typography

/**
 * Interactive tester for the shortcut subsystem — the live counterpart of `shortcuts.md`. Every rule has a card:
 * nearest-focused-binding override with disabled fall-through and blocking, focused claims vetoing keymap commands,
 * typed suppression of claimed printable keys, two-stroke chords, action-bound components agreeing with the keyboard, .
 *
 * Requires a `LocalJewelShortcutHost`; the standalone sample installs one at the window root, the IDE bridge provides
 * one per panel automatically.
 */
@Composable
public fun ShortcutTesterView(modifier: Modifier = Modifier) {
    val host = LocalJewelShortcutHost.current
    if (host == null) {
        Text("No LocalJewelShortcutHost is installed — the tester needs a shortcut host.")
        return
    }

    // Live event log: exactly one entry per completed Jewel-owned invocation.
    val events = remember { mutableStateListOf<String>() }
    LaunchedEffect(host) {
        host.events.invocations.collect { invocation ->
            events.add(0, invocation.describe())
            if (events.size > 5) events.removeAt(events.lastIndex)
        }
    }

    var outerSelectAll by remember { mutableIntStateOf(0) }
    var editorSelectAll by remember { mutableIntStateOf(0) }
    var editorOverrideEnabled by remember { mutableStateOf(true) }
    var editorBlocksOuter by remember { mutableStateOf(false) }

    var pings by remember { mutableIntStateOf(0) }
    var claimVetoes by remember { mutableIntStateOf(0) }
    var claimActive by remember { mutableStateOf(true) }

    var macroRecordings by remember { mutableIntStateOf(0) }

    var reformats by remember { mutableIntStateOf(0) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Shortcuts route through the host keymap; claims own keys ahead of it. " +
                "Click a card to focus it, then use the keystrokes it names.",
            style = JewelTheme.typography.medium,
        )

        // ----- Presentation Assistant-style event overlay -----
        Column(
            Modifier.fillMaxWidth()
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, JewelTheme.globalColors.borders.normal)
                .padding(8.dp)
        ) {
            Text("Last invocations", fontWeight = FontWeight.Bold)
            if (events.isEmpty()) Text("— nothing invoked yet —")
            for (line in events) Text(line)
        }

        // ----- 1. Nested bindings: nearest focused enabled binding wins -----
        GroupHeader("Nested bindings — ${DemoSelectAll.shortcutHint(host)}")
        Column(
            Modifier.testTag("ShortcutTester.Outer")
                .testCard()
                .shortcut(DemoSelectAll) { outerSelectAll++ }
                .focusable(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Outer surface binding — fired $outerSelectAll times (focus HERE and press it)")
            Column(
                Modifier.testTag("ShortcutTester.Editor")
                    .testCard()
                    .shortcut(DemoSelectAll, enabled = editorOverrideEnabled, blocksOuterBindings = editorBlocksOuter) {
                        editorSelectAll++
                    }
                    .focusable()
            ) {
                Text("Inner 'editor' override — fired $editorSelectAll times (focus HERE: override wins)")
            }
            CheckboxRow(
                text = "Inner override enabled (disabled falls through to the outer binding)",
                checked = editorOverrideEnabled,
                onCheckedChange = { editorOverrideEnabled = it },
            )
            CheckboxRow(
                text = "Disabled inner override blocks the outer binding instead of falling through",
                checked = editorBlocksOuter,
                onCheckedChange = { editorBlocksOuter = it },
            )
        }

        // ----- 2. Claims veto keymap commands -----
        GroupHeader("Claims veto commands — ${DemoPing.shortcutHint(host)}")
        Column(
            Modifier.testTag("ShortcutTester.Ping").testCard().shortcut(DemoPing) { pings++ }.focusable(),
            Arrangement.spacedBy(8.dp),
        ) {
            Text("Ping action — fired $pings times (focus HERE: the keymap resolves it)")
            Column(
                Modifier.testTag("ShortcutTester.Claimer")
                    .testCard()
                    .claimShortcut(
                        // Same physical stroke DemoPing is bound to (Alt+G on every OS).
                        sequence = JewelKeySequence(JewelKeyStroke(Key.G, alt = true)),
                        enabled = claimActive,
                    ) {
                        claimVetoes++
                    }
                    .focusable()
            ) {
                Text(
                    "Claiming surface — claimed $claimVetoes times (focus HERE: the same stroke is " +
                        "owned locally and Ping does NOT fire — this is the IDE-veto shape)"
                )
            }
            CheckboxRow(
                text = "Claim active while focused",
                checked = claimActive,
                onCheckedChange = { claimActive = it },
            )
        }

        // ----- 3. Typed suppression -----
        GroupHeader("Typed suppression — claimed printable K")
        Column(Modifier.testCard(), Arrangement.spacedBy(8.dp)) {
            Text("Macro recorded $macroRecordings times; a claimed printable key never types its character.")
            Row {
                val fieldState = rememberTextFieldState()
                TextField(
                    state = fieldState,
                    modifier =
                        Modifier.width(320.dp).claimShortcut(sequence = JewelKeySequence(JewelKeyStroke(Key.K))) {
                            macroRecordings++
                        },
                    placeholder = { Text("Type here: K runs the macro and is suppressed; other keys type") },
                )
            }
        }

        // ----- 4. Two-stroke chord -----
        GroupHeader("Two-stroke chord — ${DemoReformat.shortcutHint(host)}")
        Column(Modifier.testCard().shortcut(DemoReformat) { reformats++ }.focusable(), Arrangement.spacedBy(8.dp)) {
            Text("Reformat — fired $reformats times (first stroke arms the chord; a wrong second stroke cancels)")
            Text(if (host.isAwaitingSecondStroke) "… awaiting second stroke" else "chord idle")
        }
    }
}

private fun ActionInvocation.describe(): String {
    val trigger =
        when (val t = trigger) {
            is ActionTrigger.Keyboard -> "keyboard ${t.sequence?.displayText() ?: ""}".trim()
            is ActionTrigger.Pointer -> "pointer"
            is ActionTrigger.Programmatic -> "programmatic"
        }
    return "${actionId?.value ?: sequence?.displayText() ?: "claim"} — $trigger"
}

@Composable
private fun JewelAction.shortcutHint(host: JewelShortcutHostState): String =
    host.shortcutsFor(id).firstOrNull()?.displayText() ?: "unbound"

@Composable
private fun Modifier.testCard(): Modifier =
    fillMaxWidth().border(1.dp, JewelTheme.globalColors.borders.normal).padding(12.dp)
