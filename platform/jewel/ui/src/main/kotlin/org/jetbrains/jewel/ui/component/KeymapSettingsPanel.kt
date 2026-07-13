// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.shortcut.JewelActionId
import org.jetbrains.jewel.foundation.shortcut.JewelActionRegistry
import org.jetbrains.jewel.foundation.shortcut.JewelKeySequence
import org.jetbrains.jewel.foundation.shortcut.JewelKeyStroke
import org.jetbrains.jewel.foundation.shortcut.MutableJewelKeymap

/**
 * A minimal standalone keymap settings surface: lists every registered action with its effective
 * bindings in [keymap], lets the user record a replacement one-stroke binding per action, and surfaces
 * conflicts (other actions bound to the same sequence). Reacts to external keymap edits through the
 * keymap's modification count; persistence remains application policy, as for the keymap itself.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun KeymapSettingsPanel(
    keymap: MutableJewelKeymap,
    registry: JewelActionRegistry,
    modifier: Modifier = Modifier,
) {
    val modCount by keymap.modificationCount.collectAsState()
    var recordingFor by remember { mutableStateOf<JewelActionId?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val definitions = remember(registry, modCount) { registry.definitions().sortedBy { it.action.title } }
        for (definition in definitions) {
            val action = definition.action
            val shortcuts = remember(modCount, action.id) { keymap.shortcutsFor(action.id) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(action.title, Modifier.weight(1f))
                Text(shortcuts.joinToString { it.displayText() }.ifEmpty { "None" })
                if (recordingFor == action.id) {
                    RecordShortcutField(
                        onRecorded = { stroke ->
                            keymap.replaceBindings(action.id, listOf(JewelKeySequence(stroke)))
                            recordingFor = null
                        },
                        onCancel = { recordingFor = null },
                    )
                } else {
                    OutlinedButton(onClick = { recordingFor = action.id }) { Text("Rebind") }
                }
            }

            for (sequence in shortcuts) {
                val conflicts = keymap.conflicts(sequence).filter { it != action.id }
                if (conflicts.isNotEmpty()) {
                    Text(
                        text =
                            "Conflict on ${sequence.displayText()}: also bound to " +
                                conflicts.joinToString { it.value },
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
    }
}

/** Captures the next non-modifier key-down as the new binding; Escape cancels the recording. */
@Composable
private fun RecordShortcutField(onRecorded: (JewelKeyStroke) -> Unit, onCancel: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    OutlinedButton(
        onClick = onCancel,
        modifier =
            Modifier.focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    if (event.key == Key.Escape) {
                        onCancel()
                        return@onPreviewKeyEvent true
                    }
                    JewelKeyStroke.fromKeyDownOrNull(event)?.let(onRecorded)
                    // Modifier-only key-downs stay swallowed while waiting for the full stroke.
                    true
                }
                .focusable(),
    ) {
        Text("Press a shortcut…")
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
