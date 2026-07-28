// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the
// Apache 2.0 license.
package org.jetbrains.jewel.samples.showcase.views

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.jewel.foundation.actionSystem.provideData
import org.jetbrains.jewel.foundation.shortcut.ActionInvocation
import org.jetbrains.jewel.foundation.shortcut.ActionPresentationOverride
import org.jetbrains.jewel.foundation.shortcut.LocalJewelShortcutHost
import org.jetbrains.jewel.foundation.shortcut.PresentationValue
import org.jetbrains.jewel.foundation.shortcut.shortcut
import org.jetbrains.jewel.samples.showcase.LocalShowcaseActionRegistry
import org.jetbrains.jewel.samples.showcase.LocalShowcaseKeymap
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents.Archive
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents.Delete
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents.HasSelection
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents.IsOnline
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents.MainGroup
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents.NoIcon
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents.Refresh
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents.RunOptionsGroup
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents.Save
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents.ShowWhitespace
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents.Sync
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents.TextAction
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents.Unavailable
import org.jetbrains.jewel.samples.showcase.ShowcaseActionComponents.WordWrap
import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.ActionGroupButton
import org.jetbrains.jewel.ui.component.ActionToolbar
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.InfoText
import org.jetbrains.jewel.ui.component.KeymapSettingsPanel
import org.jetbrains.jewel.ui.component.SegmentedControl
import org.jetbrains.jewel.ui.component.SegmentedControlButtonData
import org.jetbrains.jewel.ui.component.SplitActionButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.ToggleActionButton
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

/**
 * Live demonstration of the action-bound components. Every control here is driven by the same host the keyboard uses:
 * the components take no handlers, they sample the focused binding's presentation and invoke through the host invoker,
 * so pointer and keyboard can never disagree about enablement or behavior.
 *
 * The whole page sits inside one focusable surface that declares the bindings, because presentation resolves against
 * the *focused* binding: unfocus this surface and every control correctly falls back to its unavailable state.
 *
 * Layout: a sticky [ActionToolbar] tops the left column, a [SegmentedControl] flips the scrolling body between the
 * component gallery and the live keymap editor, and the invocation log floats in a narrow right column.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun ActionComponentsView(modifier: Modifier = Modifier) {
    val host = LocalJewelShortcutHost.current
    if (host == null) {
        Text("No LocalJewelShortcutHost is installed — action components need a shortcut host.")
        return
    }

    // The live keymap editor needs the concrete registry + a mutable keymap; the provider hands them down (null on
    // surfaces like the DevKit tab, where the IDE owns keymap editing, so the panel is simply omitted there).
    val keymap = LocalShowcaseKeymap.current
    val registry = LocalShowcaseActionRegistry.current

    val events = remember { mutableStateListOf<String>() }
    LaunchedEffect(host) {
        host.events.invocations.collect { invocation ->
            events.add(0, invocation.describeInvocation())
            if (events.size > 50) events.removeAt(events.lastIndex)
        }
    }

    var saves by remember { mutableIntStateOf(0) }
    var refreshes by remember { mutableIntStateOf(0) }
    var noIcons by remember { mutableIntStateOf(0) }
    var commits by remember { mutableIntStateOf(0) }
    var deletes by remember { mutableIntStateOf(0) }
    var syncs by remember { mutableIntStateOf(0) }
    var archives by remember { mutableIntStateOf(0) }
    var wordWrap by remember { mutableStateOf(false) }
    var showWhitespace by remember { mutableStateOf(false) }
    var hasSelection by remember { mutableStateOf(true) }
    var showKeybindings by remember { mutableStateOf(false) }

    val surfaceFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { surfaceFocus.requestFocus() }

    // An asynchronous source feeding the action context: a Flow of connectivity, collected into Compose state at the
    // edge with collectAsState, then contributed with provideData below. Sync's enablement reads it. There is NO manual
    // presentation invalidation anywhere on this page — the standalone presentation is snapshot-reactive, so flipping
    // the selection or the connectivity flow re-derives exactly the affected controls on its own. (A real app would
    // supply a real connectivity/websocket/poll Flow here; a MutableStateFlow toggled below stands in for one.)
    val connectivity = remember { MutableStateFlow(true) }
    val isOnline by connectivity.collectAsState()

    Row(
        modifier
            .fillMaxSize()
            // The bindings live on the focused surface; the components below resolve against them.
            .shortcut(Save) { saves++ }
            .shortcut(Refresh) { refreshes++ }
            .shortcut(NoIcon) { noIcons++ }
            .shortcut(TextAction) { commits++ }
            // Delete's enablement is derived from the action context, not fixed on the binding: it enables exactly when
            // a `HasSelection` datum is present and true — the standalone shape of an AnAction.update() reading its
            // DataContext. The datum is contributed by `provideData` below.
            .shortcut(Delete, update = { enabled = context[HasSelection] == true }) { deletes++ }
            // Sync's enablement derives from the connectivity flow collected above and provided below — an async source
            // driving enablement reactively, with no manual invalidation.
            .shortcut(Sync, update = { enabled = context[IsOnline] == true }) { syncs++ }
            .shortcut(Archive, presentation = ActionPresentationOverride(visible = PresentationValue.Set(false))) {
                archives++
            }
            .shortcut(WordWrap, presentation = ActionPresentationOverride(selected = PresentationValue.Set(wordWrap))) {
                wordWrap = !wordWrap
            }
            .shortcut(
                ShowWhitespace,
                presentation = ActionPresentationOverride(selected = PresentationValue.Set(showWhitespace)),
            ) {
                showWhitespace = !showWhitespace
            }
            // Contribute the selection state into the action context. Placed before `focusable()` so the provider
            // observes the surface's focus, exactly like the shortcut bindings above; the IJPL bridge sinks the very
            // same data into the platform data context, so this one line works identically in the IDE.
            .provideData { set(HasSelection.name, hasSelection) }
            // The connectivity datum in its own provideData block: one datum per block keeps the reactive lookup
            // per-key precise, so a control reading one datum never recomputes because an unrelated one changed.
            .provideData { set(IsOnline.name, isOnline) }
            .focusRequester(surfaceFocus)
            // A plain focusable is not focused by a pointer click, and the initial request below only fires once; wire
            // a press that no child consumed back to the surface so a click on empty space re-focuses it (otherwise the
            // controls stay in their unfocused/unavailable state with no pointer way back).
            .pointerInput(surfaceFocus) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    surfaceFocus.requestFocus()
                    down.consume()
                }
            }
            .focusable()
            .testTag("ActionComponents.Surface"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Left column: a sticky toolbar, a tab switch, and the scrolling body beneath them.
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionToolbar(MainGroup, modifier = Modifier.testTag("ActionComponents.Toolbar"))

            if (keymap != null && registry != null) {
                SegmentedControl(
                    enabled = true,
                    buttons =
                        listOf(
                            SegmentedControlButtonData(
                                selected = !showKeybindings,
                                content = { _ -> Text("Components") },
                                onSelect = { showKeybindings = false },
                            ),
                            SegmentedControlButtonData(
                                selected = showKeybindings,
                                content = { _ -> Text("Keybindings") },
                                onSelect = { showKeybindings = true },
                            ),
                        ),
                )
            }

            VerticallyScrollableContainer(Modifier.weight(1f).fillMaxWidth()) {
                if (keymap != null && registry != null && showKeybindings) {
                    KeymapSettingsPanel(
                        keymap = keymap,
                        registry = registry,
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp).testTag("ActionComponents.KeymapPanel"),
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        InfoText(
                            "The toolbar above and every control below are bound to actions, not handlers — they " +
                                "render the focused binding's presentation and invoke through the host, so they " +
                                "always agree with the keyboard."
                        )

                        Section("ActionButton — presentation, enablement, visibility") {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                ActionButton(Save, modifier = Modifier.testTag("ActionComponents.Save"))
                                ActionButton(Refresh, modifier = Modifier.testTag("ActionComponents.Refresh"))
                                ActionButton(Delete, modifier = Modifier.testTag("ActionComponents.Delete"))
                                ActionButton(Unavailable, modifier = Modifier.testTag("ActionComponents.Unavailable"))
                                ActionButton(TextAction, modifier = Modifier.testTag("ActionComponents.TextAction"))
                            }
                            InfoText(
                                "'Unavailable' is unbound, so it renders disabled; 'Commit' opts into " +
                                    "SHOW_TEXT_IN_TOOLBAR and shows its label; 'No icon' falls back to the " +
                                    "unknown-action placeholder."
                            )
                            CheckboxRow(
                                text =
                                    "Has selection (Delete reads this from the action context; uncheck and the " +
                                        "button and its shortcut both disable)",
                                checked = hasSelection,
                                onCheckedChange = { hasSelection = it },
                                modifier = Modifier.testTag("ActionComponents.DeleteToggle"),
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                ActionButton(Sync, modifier = Modifier.testTag("ActionComponents.Sync"))
                                CheckboxRow(
                                    text = "Online (Sync reads this from a Flow collected into the context)",
                                    checked = isOnline,
                                    onCheckedChange = { connectivity.value = it },
                                    modifier = Modifier.testTag("ActionComponents.OnlineToggle"),
                                )
                            }
                            InfoText(
                                "'Sync' enables only while online. Its datum comes from a Flow (collectAsState → " +
                                    "provideData), not a mutableStateOf — the async-source-into-context pattern, still " +
                                    "with no manual invalidation."
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                ActionButton(
                                    Archive,
                                    modifier = Modifier.testTag("ActionComponents.ArchiveRespectingVisibility"),
                                )
                                ActionButton(
                                    Archive,
                                    modifier = Modifier.testTag("ActionComponents.ArchiveIgnoringVisibility"),
                                    respectVisibility = false,
                                )
                            }
                            InfoText(
                                "'Archive' is bound but overridden hidden: nothing on the left (respectVisibility), " +
                                    "still shown on the right (respectVisibility = false) — either way it stays " +
                                    "keymap-invocable."
                            )
                            InfoText(
                                "Fired — Save $saves · Refresh $refreshes · Delete $deletes · Sync $syncs · " +
                                    "Commit $commits · No-icon $noIcons · Archive $archives"
                            )
                        }

                        Section("ToggleActionButton — checked state is the sampled presentation") {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                ToggleActionButton(WordWrap, modifier = Modifier.testTag("ActionComponents.WordWrap"))
                                ToggleActionButton(
                                    ShowWhitespace,
                                    modifier = Modifier.testTag("ActionComponents.ShowWhitespace"),
                                )
                            }
                            InfoText(
                                "Word wrap: $wordWrap · Show whitespace: $showWhitespace — the control reflects the " +
                                    "binding's presentation override, never its own state."
                            )
                        }

                        Section("ActionGroupButton — a popup group rendered the way a toolbar renders one") {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                ActionGroupButton(MainGroup, modifier = Modifier.testTag("ActionComponents.MenuButton"))
                                ActionGroupButton(
                                    MainGroup,
                                    modifier = Modifier.testTag("ActionComponents.MenuButtonKeepOpen"),
                                    keepPopupsForToggles = true,
                                )
                            }
                            InfoText("The second menu stays open while you flip toggles.")
                        }

                        Section("SplitActionButton — primary action plus a menu") {
                            SplitActionButton(
                                primary = Save,
                                menuGroup = RunOptionsGroup,
                                modifier = Modifier.testTag("ActionComponents.SplitButton"),
                            )
                            InfoText("One button, two hit regions and a divider — Jewel's SplitButton, as in Swing.")
                        }
                    }
                }
            }
        }

        // Right column: the invocation log, floating in a narrow, scrollable rail.
        Column(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GroupHeader("Invocations")
            VerticallyScrollableContainer(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (events.isEmpty()) InfoText("Nothing invoked yet.")
                    for (line in events) Text(line)
                }
            }
        }
    }
}

/** The action id minus its package-y prefix, so the log column can stay narrow. */
private fun ActionInvocation.describeInvocation(): String {
    val id = actionId?.value?.substringAfterLast('.') ?: sequence?.displayText() ?: "claim"
    return "$id — ${trigger::class.simpleName}"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupHeader(title)
        content()
    }
}
