# Shortcuts and keymaps

Jewel's shortcut subsystem (`org.jetbrains.jewel.foundation.shortcut`, experimental) gives Compose
content a first-class way to participate in keyboard dispatch in both hosts:

- **Commands** (`Modifier.shortcut`) are host-visible actions resolved through the host keymap — the
  IntelliJ keymap in the bridge, a `JewelKeymap` in standalone applications. They respect user keymap
  customisation and are the default choice.
- **Claims** (`Modifier.claimShortcut`, `Modifier.claimKeyEvent`) let a focused component own a specific
  physical keystroke *ahead of* keymap lookup. They are deliberately local and visible in source review:
  use them for editor-like surfaces that must own keys such as <kbd>Ctrl</kbd>+<kbd>Enter</kbd>, not as a
  convenience to skip registering commands.

Precedence, in both hosts: **focused claims → host keymap commands → ordinary Compose input.** An
unbound key is never consumed.

## Binding commands and claims

```kotlin
// The nearest focused enabled binding for an action wins; disabled bindings fall
// through outward; blocksOuterBindings stops the fall-through.
Box(Modifier.shortcut(SelectAllRows) { table.selectAll() }) {
    Editor(
        Modifier
            .shortcut(SelectAllRows) { editor.selectAllText() } // overrides while focused
            .claimShortcut(JewelKeySequence(JewelKeyStroke(Key.Enter, ctrl = true))) { submit() }
    )
}
```

Binding and claim modifiers observe focus like `Modifier.provideData` does: they see the focus of
modifiers applied **after** them in the chain and of descendant nodes, so they must come before
`focusable()` in the chain.

## Standalone applications

One `JewelShortcutHostState` per window. Install its root modifier at the content root and pass its
handler to the window's **AWT-level** key hook — not a root `Modifier.onPreviewKeyEvent`, which never
sees KEY_TYPED events and therefore cannot stop a claimed printable key from inserting its character:

```kotlin
val keymap = InMemoryJewelKeymap.fromDefaults("app", actionRegistry)
val state = JewelShortcutHostState { keymapManager.activeKeymap.value }

Window(onPreviewKeyEvent = state::onPreviewKeyEvent, onCloseRequest = ...) {
    IntUiTheme {
        Box(state.resolverRootModifier) { AppContent() }
    }
}
```

- `JewelKeymap` supports named schemes, parent inheritance with `hideInherited` markers, prefix queries
  for two-stroke sequences, conflicts, and runtime mutation (`modificationCount` drives menu hints).
  Persistence is application policy. `KeymapSettingsPanel` is a minimal settings surface over a
  `MutableJewelKeymap`: it lists effective bindings per registered action, records one-stroke rebinds,
  and surfaces conflicts.
- **Popups and dialogs run in their own Compose scene layers and do not inherit window key hooks.**
  Jewel-owned menus (`PopupMenu`, context menus, `ActionMenu`) thread the shortcut host's
  `onPreviewKeyEvent` into their popups automatically when a `LocalJewelShortcutHost` is present;
  thread `state::onPreviewKeyEvent` into `Popup(onPreviewKeyEvent = …)` yourself for application
  popups. While a menu is open, its item shortcuts are absorbed into the host as a *menu scope*
  resolved ahead of ordinary dispatch, so menu-local and host dispatch can never race.

## IntelliJ bridge

`JewelComposePanel` wires the claim lane automatically:

- The panel wrapper implements `KeyboardAwareFocusOwnerProvider`, the platform's ancestor-side escape
  hatch: the actual AWT focus owner inside a `ComposePanel` is an internal skiko component that cannot
  implement `KeyboardAwareFocusOwner` itself, so `IdeKeyEventDispatcher` consults the wrapper instead.
  A focused claim makes the IDE skip keymap processing for that keystroke while ordinary AWT dispatch
  continues.
- A `KeyEventDispatcher` scoped to the wrapper's focused descendants then delivers the claimed
  key-down to the claim handler and swallows the trailing KEY_TYPED.
- **Commands stay IntelliJ actions** resolved through the IDE keymap. `JewelActionBridgeAction`
  (declare it in plugin.xml under the Jewel action ID, or let `JewelBridgeActionRegistry` register it
  at runtime) resolves the focused host from the data context — the wrapper snapshots its host state
  under a dedicated `DataKey` — and re-resolves the nearest focused enabled binding at perform time.
  It is enabled in modal contexts; `DumbAwareJewelActionBridgeAction` is the opt-in declaration
  variant for indexing-safe handlers, and runtime registrations always stay non-dumb-aware.
- The standard edit actions map onto `$Copy`/`$Cut`/`$Paste`/`$SelectAll`
  (`JewelActionMappings.installStandardMappings()`, installed by the bridge on panel composition,
  override-safe); `ComposeCopyProvider`/`ComposeCutProvider`/`ComposePasteProvider` complete the
  semantic edit-action bridge from focused Compose semantics.
- Programmatic invocations (`ActionButton` and friends) route through `JewelBridgeActionInvoker` and
  `ActionManager.tryToExecute`, so platform update, enablement, and listeners stay authoritative;
  presentation sampling rides the platform action-update cadence (a demand-gated `TimerListener`).

## Dispatch contract details

- Modifier-only key-downs (a lone <kbd>Ctrl</kbd> press) never participate in matching and never cancel
  a pending two-stroke sequence.
- Any consumed key-down arms one-shot suppression of the trailing KEY_TYPED event.
- An exact one-stroke command wins immediately over entering a two-stroke sequence sharing its first
  stroke. A pending sequence's nonmatching second stroke cancels the sequence and is consumed —
  deliberately stricter than the IntelliJ dispatcher, which consumes wrong second strokes and keeps
  waiting until a key release or timeout.
- Claims must be active when focus enters the component. Toggling a claim on between the strokes of an
  in-flight IDE chord leaves the platform's pending second-stroke state behind; it self-heals on key
  release or the `actionSystem.secondKeystrokeTimeout` timeout, but one unclaimed stroke can be
  swallowed in the interim.
- Bridge claims are one-stroke sequences in this slice; two-stroke claims and repeat policies follow.

The engine (`ShortcutDispatchEngine`) is free of Compose and AWT types; its unit tests in
`foundation/src/test/.../shortcut/` pin every rule above.

## Action-bound components

Provide the host to the composition (`ProvideJewelShortcutHost(state) { … }`; the bridge does this
automatically inside `JewelComposePanel`) and components stay in sync with keyboard dispatch:

```kotlin
ActionButton(SelectAllRows)          // renders sampled presentation (icon, text); invokes through the host
ToggleActionButton(WordWrap)         // Toggle actions; checked state = presentation.selected
ActionToolbar(mainToolbarGroup)      // leaf actions, separators, inline subgroups, popup subgroups
ActionMenuButton(viewOptionsGroup)   // opens an ActionMenu; submenus, toggle items, dismiss policies
SplitActionButton(Run, runConfigsGroup)
```

A binding contributes dynamic presentation through `Modifier.shortcut(action, presentation = …)`
(`ActionPresentationOverride`: text, description, visibility, `selected` for toggles, a
host-interpreted icon slot, and `MenuDismissPolicy` — the four-state mirror of IJPL's
`KeepPopupOnPerform`, carried as presentation state). `collectPresentationAsState(selector)` observes
one projection gated by that projection's own equality. Failure rows are explicit
(`ActionResolution.Unregistered` — coalesced diagnostics — `NoFocusedBinding`, `HostUnavailable`), and
execution always re-resolves, so a stale presentation can never invoke a gone binding.

Presentation sampling is demand-driven and equality-gated (`ActionPresentationScheduler`): controls
register demand while composed, polls re-sample only on host signals (dispatches, `invalidate()`),
and unchanged samples cause no recomposition. `JewelShortcutHostState.events` emits exactly one
`ActionInvocation` per completed Jewel-owned invocation — the Presentation Assistant/analytics hook.
Repeat delivery is per binding/claim via `ShortcutRepeatPolicy` (`OnceUntilRelease` suppresses
delivered auto-repeats until key-up).

## Testing your shortcuts

Shortcuts are verifiable in plain Compose UI tests — no window, no AWT hooks, no IDE fixture:

- The resolver root participates in the scene's key-event **preview pass**, so idiomatic scene
  injection drives real dispatch (focus resolution, fall-through, claims, chords):

  ```kotlin
  composeRule.setContent { Box(state.resolverRootModifier) { AppContent() } }
  composeRule.onNodeWithTag("editor").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
  ```

  In production this scene-level lane coexists safely with the AWT hooks: events they consume never
  reach the scene, and events they passed re-evaluate to the same pass without mutating dispatch
  state. KEY_TYPED suppression remains the AWT hooks' job — test scenes never produce typed events,
  so assert typed suppression at the engine level or in the live harnesses.

- Bindings and claims publish **semantics**, so tests (and tooling) can discover keyboard behavior:
  `JewelShortcutActions` carries the bound action IDs, `JewelClaimedShortcuts` the claimed sequences'
  display texts:

  ```kotlin
  composeRule.onNodeWithTag("editor")
      .assert(SemanticsMatcher.expectValue(JewelClaimedShortcuts, listOf("Ctrl+Enter")))
  ```

`ShortcutUiTest` in the foundation test sources demonstrates both.

## Status

Implemented and tested: dispatch core (incl. repeat policies and menu scopes), standalone resolver,
keymap model + settings surface, presentation model (overrides, icons, failure rows, selector
projections) + scheduler, action events, group model, `ActionButton`/`ToggleActionButton`/
`ActionToolbar`/`ActionMenu`/`ActionMenuButton`/`SplitActionButton`, the bridge claim lane, and the
bridge action registry (attach-or-register, standard edit mappings, platform-routed invoker,
platform-cadence presentation updates) with platform-level integration tests. Remaining: the
multi-OS conflict/IME proof-matrix rows, which need live per-OS validation.
