// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide

import com.intellij.openapi.keymap.impl.IdeKeyEventDispatcher
import org.jetbrains.annotations.ApiStatus
import java.awt.Component
import java.awt.event.KeyEvent

/**
 * Ancestor-side counterpart of [KeyboardAwareFocusOwner].
 *
 * Some embedded UI toolkits (Compose, native webviews, and other canvas-based renderers) place AWT focus on an
 * internal component that the embedder does not control and therefore cannot make implement
 * [KeyboardAwareFocusOwner]. A container implementing this interface can make the same decision on behalf of
 * such a focused descendant: when the focus owner does not itself skip dispatch, [IdeKeyEventDispatcher] walks
 * the focus owner's ancestor chain and lets the first ancestor returning `true` from [skipKeyEventDispatcher]
 * prevent IDE shortcut processing, while ordinary AWT dispatch continues to the focused component.
 *
 * Implementations must be fast: this is consulted on the EDT for every key event while a descendant of the
 * implementing container is focused. Return `true` only for events the focused content explicitly claims;
 * returning `true` unconditionally disables all IDE shortcuts while the descendant is focused.
 *
 * Contract notes, pinned by `KeyboardAwareFocusOwnerProviderTest`:
 * - Returning `true` only shields the event from the IDE keymap — it does not consume it. If the embedder
 *   needs the event consumed or delivered specially, it must do so in its own input pipeline (for example,
 *   via a [java.awt.KeyEventDispatcher] scoped to its focused descendants).
 * - `KEY_TYPED` events that follow a skipped `KEY_PRESSED` are not swallowed by the dispatcher (unlike after
 *   a performed action); an embedder claiming printable keys must suppress the typed character itself.
 * - A claim that begins between the strokes of an in-flight keymap chord leaves the dispatcher's pending
 *   second-stroke state behind until a key release or the `actionSystem.secondKeystrokeTimeout` timeout.
 *   Claim sets should therefore be in place when focus enters the component, not toggled mid-chord.
 *
 * The primary consumer is Jewel's `JewelComposePanelWrapper`; see `platform/jewel/docs/shortcuts.md`.
 */
@ApiStatus.Experimental
interface KeyboardAwareFocusOwnerProvider {
  /**
   * @param focusOwner the focused descendant the event is targeted at
   * @param event the key event about to be processed by [IdeKeyEventDispatcher]
   * @return `true` to skip IDE shortcut processing for this event; ordinary AWT dispatch still delivers it
   */
  fun skipKeyEventDispatcher(focusOwner: Component, event: KeyEvent): Boolean
}
