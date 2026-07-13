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
  Persistence is application policy.
- **Popups and dialogs run in their own Compose scene layers and do not inherit window key hooks.**
  Thread `state::onPreviewKeyEvent` into `Popup(onPreviewKeyEvent = …)` for any popup that must keep
  dispatch working while open. Jewel-owned popups will do this as the menu integration lands.

## IntelliJ bridge

`JewelComposePanel` wires the claim lane automatically:

- The panel wrapper implements `KeyboardAwareFocusOwnerProvider`, the platform's ancestor-side escape
  hatch: the actual AWT focus owner inside a `ComposePanel` is an internal skiko component that cannot
  implement `KeyboardAwareFocusOwner` itself, so `IdeKeyEventDispatcher` consults the wrapper instead.
  A focused claim makes the IDE skip keymap processing for that keystroke while ordinary AWT dispatch
  continues.
- A `KeyEventDispatcher` scoped to the wrapper's focused descendants then delivers the claimed
  key-down to the claim handler and swallows the trailing KEY_TYPED.
- **Commands stay IntelliJ actions** resolved through the IDE keymap; the bridge action registry
  (`JewelActionBridgeAction`, standard edit-action mappings) is the next implementation slice.

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

## Status

Implemented: the dispatch core, standalone resolver, keymap model, and the bridge claim lane.
Planned next: the bridge action registry and standard edit actions; presentation
collection/`ActionButton`; repeat policies; groups, menus, and toolbars; keymap settings surfaces.
