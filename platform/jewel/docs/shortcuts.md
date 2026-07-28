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

> [!WARNING]
> **Anti-pattern — a shortcut placed *after* `focusable()` is silently inert.** Because the modifier
> only observes focus of nodes that come after it, a binding or claim applied after `focusable()`
> never sees its own node gain focus, so it never registers with the resolver root. There is no
> runtime error: the control renders **disabled** (it samples `NoFocusedBinding`) and its handler
> **never fires**. This is the same ordering contract as `Modifier.provideData`.
>
> ```kotlin
> // WRONG: .shortcut after .focusable() — renders disabled, never invokes.
> Box(Modifier.focusable().shortcut(SelectAllRows) { table.selectAll() })
>
> // RIGHT: .shortcut before .focusable().
> Box(Modifier.shortcut(SelectAllRows) { table.selectAll() }.focusable())
> ```

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
- Claims are one-stroke sequences in this slice, and `Modifier.claimShortcut` enforces it: passing a
  two-stroke sequence throws `IllegalArgumentException`, because a claim resolves on a single key-down
  and a chord claim could never be invoked yet would shadow its first stroke. The IJPL bridge veto
  shares the engine's claim resolution (`ShortcutDispatchEngine.claimsStroke`), so the host never skips
  its own keymap for a stroke Jewel would not deliver. Two-stroke claims may follow in a later slice.
- Chord and typed-suppression state resets when focus leaves the resolver root's subtree (and on host
  disposal), so returning focus never encounters a half-armed two-stroke sequence.

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

Presentation is derived two ways, chosen by the host (`JewelShortcutHostState.reactivePresentation`),
and controls never see which — they call `action.collectPresentationAsState(host)` and read the result:

- **Standalone (the default): Compose-snapshot-reactive.** See the next section.
- **IJPL bridge: demand-driven and equality-gated** (`ActionPresentationScheduler`). Controls register
  demand while composed; polls re-sample only on host signals (dispatches, the platform's action-update
  timer, `invalidate()`); unchanged samples cause no recomposition. The bridge uses this because it wraps
  the platform `DataContext`, which cannot be observed as Compose snapshot state.

`JewelShortcutHostState.events` emits exactly one `ActionInvocation` per completed Jewel-owned invocation
— the Presentation Assistant/analytics hook. Repeat delivery is per binding/claim via `ShortcutRepeatPolicy`
(`OnceUntilRelease` suppresses delivered auto-repeats until key-up).

## Standalone presentation is Compose-snapshot-reactive

Standalone, a control's presentation is a `derivedStateOf` over the focused bindings and the live
`Modifier.provideData` values, so it updates **with no manual invalidation** when the snapshot state an
`update` block reads changes, and **only** the affected controls recompose. There is no scheduler and no
timer on this path.

```kotlin
// hasSelection is plain Compose state. No host.presentations.invalidate() anywhere:
var hasSelection by remember { mutableStateOf(true) }

Modifier
    .provideData { set(HasSelection.name, hasSelection) }
    .shortcut(Delete, update = { enabled = context[HasSelection] == true }) { deleteSelection() }

ActionButton(Delete)   // re-derives — button and shortcut agree — the moment hasSelection flips
```

How it works: each control's presentation is `remember(host, id) { derivedStateOf { … } }` that resolves
the focused binding for `id` and runs its `update` block against a **live** `ActionContext`. That
context's `get(key)` reads through the focused providers per key, so evaluating `context[HasSelection]`
transitively reads the `mutableStateOf` behind `provideData`. Compose then recomposes exactly the controls
whose reads changed; `derivedStateOf` equality-gates the result, so an unchanged presentation recomposes
nothing.

**Per-key precision.** The live lookup resolves each key nearest-provider-first and stops at the first
provider that supplies it, so a control reading one datum does not subscribe to unrelated data. Keep one
datum per `provideData` block to preserve that precision — a block that sets several keys is read as a unit,
so a control reading any one of them recomputes when any of them changes.

### Feeding an asynchronous source into the context

A value that is not itself Compose snapshot state — a connectivity `Flow`, a websocket, a poll — enters the
reactive presentation **at the edge** with `collectAsState`/`produceState`, then `provideData`. Nothing else
changes; still no manual invalidation:

```kotlin
val isOnline by connectivity.isOnline.collectAsState(initial = false)

Modifier
    .provideData { set(IsOnline.name, isOnline) }
    .shortcut(Sync, update = { enabled = context[IsOnline] == true }) { sync() }
```

Even an imperative or callback-based source is brought in this way — wrap it in `produceState` and feed the
result through `provideData`. Standalone has no manual-invalidation escape hatch, and needs none: the reactive
derivation recomputes whenever the snapshot state it reads changes. (`invalidate()` is the host's internal
presentation-cadence hook — the IJPL bridge re-samples on the platform's action-update timer — not an
app-facing API.)

### `update` reads only the context — deliberately

An `update` block is a **non-`@Composable` pure predicate** (`ActionUpdateScope.() -> Unit`), and it stays
that way on purpose. It is not the reactivity mechanism — the reactivity comes from evaluating the block
inside the `derivedStateOf` — so making it composable would buy nothing and cost the guarantees that make it
safe: being non-composable structurally forbids `remember`, effects, launched coroutines, and held state in
enablement, the same fast, pure-predicate contract as `AnAction.update()`. The corollary is the guardrail:
**enablement can be driven only by context values.** The derivation tracks the block's context reads and
nothing else, so any other input a block might consult is inert — it will not trigger a re-derivation.
Everything an action's enablement depends on must arrive through the context (via `provideData`, including
the async-into-context pattern above). Dispatch evaluates the very same block imperatively against the
current context, so the keystroke and the rendered control never disagree.

## Threading

The subsystem has a deliberate two-tier threading contract:

- **Dispatch is UI-thread-synchronous.** The handlers bound through `Modifier.shortcut`,
  `Modifier.claimShortcut`, and `Modifier.claimKeyEvent`, and the host entry points that invoke them
  (`onPreviewKeyEvent`, `claimsKeyDown`, `runResolvedInvocation`), run on the surface's UI thread —
  the AWT event dispatch thread in production — while a key event is being processed. Handlers must
  be fast and non-blocking: launch a coroutine for real work; a slow handler delays every subsequent
  keystroke. Violations are logged as errors; enable `JewelFlags.strictMode` (or the
  `jewel.strictMode` system property) to make them throw instead — keep strict mode on in
  development and tests.
- **Presentation sampling reads an atomically published snapshot and is eventually consistent.**
  `presentationFor` and the presentation scheduler read an atomically published snapshot of the
  focused bindings and may observe state up to one UI frame stale, which the next demand-driven
  sample corrects. That snapshot read is thread-safe, but whether sampling as a whole is safe from
  any thread depends on the backing: the IJPL bridge backing samples from background action updates
  (`ActionUpdateThread.BGT`) and reads the platform data context, so it is; with the standalone
  default backing, resolving a binding that computes its presentation from the focused Compose tree
  must run on the surface's UI thread. Presentation is advisory: dispatch itself never consults it.

`InMemoryJewelKeymap` and `DefaultJewelActionRegistry` are safe to read from any thread; perform
writes (binding, rebinding, action registration) on the UI thread.

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

`ShortcutUiTest` in the foundation test sources demonstrates both, and `ShortcutTesterUiTest` in the
showcase module shows the same pattern against real application UI.

## Try it live

The standalone sample is the living demo: it installs one host at the window root (see
`ShowcaseShortcutHost` and `Main.kt`), binds ambient page-navigation commands
(<kbd>Alt</kbd>+<kbd>W</kbd>/<kbd>C</kbd>/<kbd>M</kbd>/<kbd>S</kbd>), switches component sections with
<kbd>⌘</kbd>/<kbd>Ctrl</kbd>+<kbd>↑</kbd>/<kbd>↓</kbd>, and ships a **Shortcuts** page
(<kbd>Alt</kbd>+<kbd>S</kbd>) — an interactive tester covering nested overrides, claims vetoing
commands, typed suppression, chords, action components, and live keymap rebinding.

## Status

Implemented and tested: dispatch core (incl. repeat policies and menu scopes), standalone resolver,
keymap model + settings surface, presentation model (overrides, icons, failure rows, selector
projections), the Compose-snapshot-reactive standalone presentation (live per-key context, per-control
`derivedStateOf`, no manual invalidation) and the demand-driven scheduler the bridge rides, action
events, group model, `ActionButton`/`ToggleActionButton`/`ActionToolbar`/`ActionMenu`/`ActionMenuButton`/
`SplitActionButton`, the bridge claim lane, and the bridge action registry (attach-or-register, standard
edit mappings, platform-routed invoker, platform-cadence presentation updates) with platform-level
integration tests. Remaining: the multi-OS conflict/IME proof-matrix rows, which need live per-OS
validation.
