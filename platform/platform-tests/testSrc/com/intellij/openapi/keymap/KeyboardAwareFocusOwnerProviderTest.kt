// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.keymap

import com.intellij.ide.DataManager
import com.intellij.ide.KeyboardAwareFocusOwner
import com.intellij.ide.KeyboardAwareFocusOwnerProvider
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.ex.KeymapManagerEx
import com.intellij.openapi.keymap.impl.IdeKeyEventDispatcher
import com.intellij.openapi.keymap.impl.KeymapImpl
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.DefaultKeyboardFocusManager
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke

/**
 * Covers the [KeyboardAwareFocusOwnerProvider] escape hatch: an ancestor of the focus owner may skip IDE
 * shortcut processing on behalf of a focused component it cannot control (e.g., the internal canvas of an
 * embedded renderer such as a Compose panel).
 *
 * Deliberately includes the known sharp edges, so the contract is documented by tests rather than left
 * to look better than it is:
 * - a provider cannot make the dispatcher *consume* events, only protect them from the keymap;
 * - KEY_TYPED events still pass through when a pressed event was claimed (the embedder must suppress the
 *   typed character itself, mirroring what the dispatcher does internally with `ignoreNextKeyTypedEvent`);
 * - a claim that starts while the dispatcher awaits a second keystroke leaves that pending state behind,
 *   and the next unclaimed stroke is swallowed as a wrong second stroke before the state self-heals.
 */
@TestApplication
@RunInEdt(writeIntent = true)
internal class KeyboardAwareFocusOwnerProviderTest {
  private companion object {
    const val KEYMAP_NAME = "KeyboardAwareFocusOwnerProviderTestKeymap"
    const val SINGLE_ACTION = "!!!ProviderTestSingleAction"
    const val OTHER_ACTION = "!!!ProviderTestOtherAction"
    const val CHORD_ACTION = "!!!ProviderTestChordAction"

    val SINGLE_STROKE: KeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK)
    val OTHER_STROKE: KeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK)
    val CHORD_FIRST: KeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK)
    val CHORD_SECOND: KeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK)
  }

  private class CountingAction : AnAction() {
    val invocations = AtomicInteger()

    init {
      isEnabledInModalContext = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
      invocations.incrementAndGet()
    }
  }

  /** Stands in for the internal focused component of an embedded renderer (e.g., a skiko canvas). */
  private class InnerComponent : JTextField()

  private class SkippingInnerComponent : JTextField(), KeyboardAwareFocusOwner {
    override fun skipKeyEventDispatcher(event: KeyEvent): Boolean = true
  }

  private class ProviderPanel : JPanel(), KeyboardAwareFocusOwnerProvider {
    var claim: (KeyEvent) -> Boolean = { false }
    val consultedEvents = mutableListOf<KeyEvent>()
    val consultedFocusOwners = mutableListOf<Component>()

    override fun skipKeyEventDispatcher(focusOwner: Component, event: KeyEvent): Boolean {
      consultedEvents.add(event)
      consultedFocusOwners.add(focusOwner)
      return claim(event)
    }
  }

  private class StubFocusManager(private val owner: Component) : DefaultKeyboardFocusManager() {
    override fun getFocusOwner(): Component = owner
  }

  private lateinit var keymap: KeymapImpl
  private lateinit var savedKeymap: Keymap
  private lateinit var singleAction: CountingAction
  private lateinit var otherAction: CountingAction
  private lateinit var chordAction: CountingAction

  @BeforeEach
  fun setUp() {
    // dispatchKeyEvent silently returns false when the DataManager service was never instantiated
    DataManager.getInstance()

    keymap = KeymapImpl()
    keymap.name = KEYMAP_NAME
    KeymapManagerEx.getInstanceEx().schemeManager.addScheme(keymap, false)
    savedKeymap = KeymapManagerEx.getInstanceEx().activeKeymap
    KeymapManagerEx.getInstanceEx().activeKeymap = keymap

    singleAction = CountingAction()
    otherAction = CountingAction()
    chordAction = CountingAction()
    ActionManager.getInstance().registerAction(SINGLE_ACTION, singleAction)
    ActionManager.getInstance().registerAction(OTHER_ACTION, otherAction)
    ActionManager.getInstance().registerAction(CHORD_ACTION, chordAction)
    keymap.addShortcut(SINGLE_ACTION, KeyboardShortcut(SINGLE_STROKE, null))
    keymap.addShortcut(OTHER_ACTION, KeyboardShortcut(OTHER_STROKE, null))
    keymap.addShortcut(CHORD_ACTION, KeyboardShortcut(CHORD_FIRST, CHORD_SECOND))
  }

  @AfterEach
  fun tearDown() {
    KeyboardFocusManager.setCurrentKeyboardFocusManager(null)
    ActionManager.getInstance().unregisterAction(SINGLE_ACTION)
    ActionManager.getInstance().unregisterAction(OTHER_ACTION)
    ActionManager.getInstance().unregisterAction(CHORD_ACTION)
    KeymapManagerEx.getInstanceEx().activeKeymap = savedKeymap
    KeymapManagerEx.getInstanceEx().schemeManager.removeScheme(keymap)
  }

  private fun focusedHierarchy(inner: Component = InnerComponent()): Pair<ProviderPanel, Component> {
    val panel = ProviderPanel()
    panel.add(inner)
    KeyboardFocusManager.setCurrentKeyboardFocusManager(StubFocusManager(inner))
    return panel to inner
  }

  private fun pressed(source: Component, stroke: KeyStroke): KeyEvent =
    KeyEvent(source, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), stroke.modifiers, stroke.keyCode, KeyEvent.CHAR_UNDEFINED)

  private fun released(source: Component, stroke: KeyStroke): KeyEvent =
    KeyEvent(source, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), stroke.modifiers, stroke.keyCode, KeyEvent.CHAR_UNDEFINED)

  private fun typed(source: Component, char: Char): KeyEvent =
    KeyEvent(source, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, char)

  @Test
  fun `no provider in hierarchy leaves dispatch unchanged`() {
    val inner = InnerComponent()
    val plainParent = JPanel()
    plainParent.add(inner)
    KeyboardFocusManager.setCurrentKeyboardFocusManager(StubFocusManager(inner))

    val dispatcher = IdeKeyEventDispatcher(null)
    assertTrue(dispatcher.dispatchKeyEvent(pressed(inner, SINGLE_STROKE)))
    assertEquals(1, singleAction.invocations.get())
  }

  @Test
  fun `claiming ancestor prevents keymap action and leaves event unconsumed`() {
    val (panel, inner) = focusedHierarchy()
    panel.claim = { true }

    val dispatcher = IdeKeyEventDispatcher(null)
    val event = pressed(inner, SINGLE_STROKE)
    assertFalse(dispatcher.dispatchKeyEvent(event), "a skipped event must not be reported as dispatched")
    assertFalse(event.isConsumed, "the provider protects the event from the keymap; it must not consume it")
    assertEquals(0, singleAction.invocations.get())
    assertEquals(listOf<Component>(inner), panel.consultedFocusOwners)
  }

  @Test
  fun `selective claim leaves other shortcuts working`() {
    val (panel, inner) = focusedHierarchy()
    panel.claim = { it.keyCode == SINGLE_STROKE.keyCode }

    val dispatcher = IdeKeyEventDispatcher(null)
    assertFalse(dispatcher.dispatchKeyEvent(pressed(inner, SINGLE_STROKE)))
    assertTrue(dispatcher.dispatchKeyEvent(pressed(inner, OTHER_STROKE)))
    assertEquals(0, singleAction.invocations.get())
    assertEquals(1, otherAction.invocations.get())
  }

  @Test
  fun `provider returning false falls through to normal processing`() {
    val (panel, inner) = focusedHierarchy()
    panel.claim = { false }

    val dispatcher = IdeKeyEventDispatcher(null)
    assertTrue(dispatcher.dispatchKeyEvent(pressed(inner, SINGLE_STROKE)))
    assertEquals(1, singleAction.invocations.get())
    assertEquals(1, panel.consultedEvents.size, "provider must have been consulted exactly once")
  }

  @Test
  fun `focus owner implementing KeyboardAwareFocusOwner wins before any ancestor is consulted`() {
    val (panel, inner) = focusedHierarchy(inner = SkippingInnerComponent())
    panel.claim = { true }

    val dispatcher = IdeKeyEventDispatcher(null)
    assertFalse(dispatcher.dispatchKeyEvent(pressed(inner, SINGLE_STROKE)))
    assertEquals(0, singleAction.invocations.get())
    assertTrue(panel.consultedEvents.isEmpty(), "the exact-focus-owner escape hatch must take precedence")
  }

  @Test
  fun `innermost provider is consulted first and outer provider can still claim`() {
    val inner = InnerComponent()
    val innerPanel = ProviderPanel()
    val outerPanel = ProviderPanel()
    innerPanel.add(inner)
    outerPanel.add(innerPanel)
    KeyboardFocusManager.setCurrentKeyboardFocusManager(StubFocusManager(inner))
    innerPanel.claim = { false }
    outerPanel.claim = { true }

    val dispatcher = IdeKeyEventDispatcher(null)
    assertFalse(dispatcher.dispatchKeyEvent(pressed(inner, SINGLE_STROKE)))
    assertEquals(0, singleAction.invocations.get())
    assertEquals(1, innerPanel.consultedEvents.size, "inner provider consulted first")
    assertEquals(1, outerPanel.consultedEvents.size, "outer provider consulted after inner declined")
  }

  /**
   * Sharp edge #1: the provider cannot use the hatch to swallow input. Returning `true` only skips keymap
   * processing; the event continues through ordinary AWT dispatch either way. Consumption is the embedder's
   * job in its own input pipeline.
   */
  @Test
  fun `provider cannot consume events through the hatch`() {
    val (panel, inner) = focusedHierarchy()
    panel.claim = { true }

    val dispatcher = IdeKeyEventDispatcher(null)
    val event = pressed(inner, SINGLE_STROKE)
    assertFalse(dispatcher.dispatchKeyEvent(event))
    assertFalse(event.isConsumed)
  }

  /**
   * Sharp edge #2: when the dispatcher performs an action for a pressed event it swallows the following
   * KEY_TYPED itself. A provider claim gets no such service: the typed character still travels to the
   * focused component, and the embedder must suppress it in its own input pipeline (see the PRD's
   * consumption contract).
   */
  @Test
  fun `typed event after claimed pressed event is not swallowed by the dispatcher`() {
    val (panel, inner) = focusedHierarchy()

    // Baseline: unclaimed pressed event performs the action and the dispatcher swallows the KEY_TYPED.
    panel.claim = { false }
    val dispatcher = IdeKeyEventDispatcher(null)
    assertTrue(dispatcher.dispatchKeyEvent(pressed(inner, SINGLE_STROKE)))
    assertTrue(dispatcher.dispatchKeyEvent(typed(inner, '<')), "typed event after a performed action is swallowed")
    // The one-shot typed suppression armed by the performed action is cleared by the next non-typed
    // event that reaches the dispatcher's state machine. A real input stream always contains this key
    // release; without it the stale flag would swallow the typed event asserted on below (a vetoed
    // press returns before the flag bookkeeping, exactly like the exact-focus-owner hatch).
    assertFalse(dispatcher.dispatchKeyEvent(released(inner, SINGLE_STROKE)))

    // Claimed pressed event: the typed event is NOT swallowed and would reach the focused component.
    panel.claim = { it.id == KeyEvent.KEY_PRESSED }
    assertFalse(dispatcher.dispatchKeyEvent(pressed(inner, SINGLE_STROKE)))
    assertFalse(dispatcher.dispatchKeyEvent(typed(inner, '<')), "typed event after a claimed press leaks through")
  }

  /**
   * Sharp edge #3: a claim that begins while the dispatcher is waiting for a second keystroke leaves the
   * pending chord state behind. The stale state then swallows the next unclaimed stroke as a wrong second
   * stroke before normal processing resumes. (In production the state also self-heals via the
   * `actionSystem.secondKeystrokeTimeout` registry timeout, which this test does not wait for.)
   */
  @Test
  fun `claim starting mid-chord leaves pending state that swallows one unclaimed stroke`() {
    val (panel, inner) = focusedHierarchy()
    panel.claim = { false }

    val dispatcher = IdeKeyEventDispatcher(null)

    // First stroke of the chord: dispatcher enters the wait-for-second-stroke state.
    assertTrue(dispatcher.dispatchKeyEvent(pressed(inner, CHORD_FIRST)))
    assertTrue(dispatcher.isWaitingForSecondKeyStroke)
    assertEquals(0, chordAction.invocations.get())

    // A claim begins (conceptually: focus moved into an embedded editor that owns this key).
    panel.claim = { true }
    assertFalse(dispatcher.dispatchKeyEvent(pressed(inner, CHORD_SECOND)))
    assertEquals(0, chordAction.invocations.get(), "claimed second stroke must not perform the chord action")
    assertTrue(dispatcher.isWaitingForSecondKeyStroke, "the pending chord state is orphaned by the claim")

    // The claim ends; the next unclaimed stroke is swallowed as a wrong second stroke: the single-stroke
    // action does NOT run even though its shortcut was pressed. This is the documented trap.
    panel.claim = { false }
    assertTrue(dispatcher.dispatchKeyEvent(pressed(inner, SINGLE_STROKE)))
    assertEquals(0, singleAction.invocations.get(), "stale chord state swallows the next stroke")

    // Releasing the key resets the state machine; after that, dispatch behaves normally again.
    assertFalse(dispatcher.dispatchKeyEvent(released(inner, SINGLE_STROKE)))
    assertTrue(dispatcher.dispatchKeyEvent(pressed(inner, SINGLE_STROKE)))
    assertEquals(1, singleAction.invocations.get(), "dispatch recovers after the stale state clears")
  }

  /**
   * Companion to sharp edge #3: a claim that covers the *whole* chord from the first stroke never lets the
   * dispatcher enter the pending state, so nothing is orphaned. This is the behavior the Jewel resolver
   * must guarantee: claims must start before a chord's first stroke, not between strokes.
   */
  @Test
  fun `claim covering the whole chord never enters pending state`() {
    val (panel, inner) = focusedHierarchy()
    panel.claim = { true }

    val dispatcher = IdeKeyEventDispatcher(null)
    assertFalse(dispatcher.dispatchKeyEvent(pressed(inner, CHORD_FIRST)))
    assertFalse(dispatcher.isWaitingForSecondKeyStroke)
    assertFalse(dispatcher.dispatchKeyEvent(pressed(inner, CHORD_SECOND)))
    assertEquals(0, chordAction.invocations.get())

    // After the claim ends, the chord works normally.
    panel.claim = { false }
    assertTrue(dispatcher.dispatchKeyEvent(pressed(inner, CHORD_FIRST)))
    assertTrue(dispatcher.isWaitingForSecondKeyStroke)
    assertTrue(dispatcher.dispatchKeyEvent(pressed(inner, CHORD_SECOND)))
    assertEquals(1, chordAction.invocations.get())
  }
}
