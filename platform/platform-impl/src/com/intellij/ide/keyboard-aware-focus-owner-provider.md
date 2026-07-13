# `KeyboardAwareFocusOwnerProvider` — ancestor-aware keymap escape hatch (prototype)

Status: **prototype for review** · Related: Jewel shortcuts/keymap PRD, IJPL-212347

## Problem

`IdeKeyEventDispatcher` offers exactly one way for focused UI to opt out of IDE shortcut processing:
the focused component itself implements [`KeyboardAwareFocusOwner`](KeyboardAwareFocusOwner.java), checked
at the very top of `dispatchKeyEvent` before any keymap lookup or chord state handling:

```kotlin
val focusOwner = focusManager.focusOwner
if (focusOwner is KeyboardAwareFocusOwner && focusOwner.skipKeyEventDispatcher(e)) return false
```

That works when the embedder controls the class of the focused component (editor inlays,
`SwingWebViewHostPanel`). It cannot work for embedded renderers whose focused component is created
internally by the toolkit:

- A `ComposePanel` (Compose Multiplatform) never holds focus itself. The real focus owner is an internal
  skiko component — with `-Dcompose.swing.render.on.graphics=true` (which the IDE sets unconditionally,
  see `BuildContextImpl`) it is an anonymous `SkiaSwingLayer` subclass created by an internal factory.
  There is no public hook in Compose to substitute or decorate that class, `ComposePanel` is final, and
  an interface cannot be retrofitted onto an existing instance.
- The same shape applies to any canvas-based embedded renderer.

Without this, the Jewel shortcuts/keymap PRD's "raw claim" lane (a focused Compose editor owning e.g.
<kbd>Ctrl+Enter</kbd> ahead of an IDE action) is unimplementable: the wrapper panel Jewel *does* control is an
ancestor of the focus owner, and ancestors are never consulted.

## Change

Three production files:

1. **New** [`KeyboardAwareFocusOwnerProvider`](KeyboardAwareFocusOwnerProvider.kt) (`@ApiStatus.Experimental`):
   the ancestor-side counterpart of `KeyboardAwareFocusOwner`. Receives the actual focus owner alongside the
   event, so one provider can serve multiple focused descendants and stay selective.
2. **`IdeKeyEventDispatcher.dispatchKeyEvent`**: when the focus owner itself does not skip the event, walk
   the focus owner's ancestor chain; the first `KeyboardAwareFocusOwnerProvider` returning `true` makes the
   dispatcher return `false` (skip IDE shortcut processing; ordinary AWT dispatch continues). The walk is
   bounded by the window and costs a few reference hops per key event.
3. **`api-dump-experimental.txt`**: entry for the new interface (hand-written; regenerate with the API dump
   tooling if the format check complains).

### Inert by construction

Nothing in the platform implements the new interface. The ancestor walk only changes behavior when a
component in the focused hierarchy explicitly opts in, so the change is a provable no-op for every existing
focus owner. Precedence is unchanged: the exact-focus-owner hatch is checked first and short-circuits the
walk (covered by test).

## Semantics — including the sharp edges

The regression test
(`platform/platform-tests/testSrc/com/intellij/openapi/keymap/KeyboardAwareFocusOwnerProviderTest.kt`)
deliberately pins the *unflattering* parts of the contract, so consumers don't discover them in production:

| Behavior | Test |
|---|---|
| No provider present → dispatch unchanged | `no provider in hierarchy leaves dispatch unchanged` |
| Claim prevents the keymap action; event stays unconsumed for AWT | `claiming ancestor prevents keymap action…` |
| Claims are per-event; unclaimed shortcuts keep working | `selective claim leaves other shortcuts working` |
| Provider returning `false` falls through to normal processing | `provider returning false falls through…` |
| Exact-focus-owner hatch wins; providers not even consulted | `focus owner implementing KeyboardAwareFocusOwner wins…` |
| Innermost provider consulted first; outer may still claim | `innermost provider is consulted first…` |
| **A provider cannot consume events** — the hatch only shields them from the keymap. Consumption is the embedder's job in its own input pipeline. | `provider cannot consume events through the hatch` |
| **KEY_TYPED leaks**: after a *performed* action the dispatcher swallows the following typed event (`ignoreNextKeyTypedEvent`); after a *claimed* press it does not. The embedder must suppress the typed character itself (Compose: consumable at the `ComposeSceneMediator` pre-scene hook; validated separately with a spike). | `typed event after claimed pressed event is not swallowed…` |
| **Orphaned chord state**: a claim that starts *between* the strokes of an IDE chord leaves the dispatcher in wait-for-second-stroke state; the next unclaimed stroke is swallowed as a wrong second stroke before the state self-heals (key release, or the `actionSystem.secondKeystrokeTimeout` registry timeout, default 2 s). Consumers must start claims before a chord's first stroke — in practice: a claim set must be active when focus enters the component, not toggled mid-chord. | `claim starting mid-chord leaves pending state…` |
| A claim active from the first stroke never enters IDE chord state — nothing orphaned | `claim covering the whole chord never enters pending state` |

## Running

```bash
# dedicated convenience target
bazel test //platform/platform-tests:keyboard_aware_focus_owner_provider_test

# equivalent, via the module-wide target
bazel test //platform/platform-tests:tests_test \
  --test_filter=com.intellij.openapi.keymap.KeyboardAwareFocusOwnerProviderTest

# JPS alternative
./tests.cmd -Dintellij.build.test.patterns=com.intellij.openapi.keymap.KeyboardAwareFocusOwnerProviderTest
```

Use `./bazel.cmd` from the repository root if you don't have bazel/bazelisk on the PATH.

## How Jewel consumes this (sketch)

```kotlin
internal class JewelComposePanelWrapper(...) : BorderLayoutPanel(), UiDataProvider, KeyboardAwareFocusOwnerProvider {
  override fun skipKeyEventDispatcher(focusOwner: Component, event: KeyEvent): Boolean {
    // Read the immutable claim snapshot published by focused Modifier.claimShortcut/claimKeyEvent nodes.
    // Must be fast (EDT, every key event) and selective (only explicitly claimed strokes).
    return claimSnapshot.claims(event)
  }
}
```

The claimed event then reaches the `ComposePanel` through ordinary AWT dispatch; the Jewel resolver delivers
it (and suppresses the trailing `KEY_TYPED` when it consumed a press) at the Compose pre-scene hook.

## Known limitations / open questions for review

- **Not run in this environment**: the test was written against the current dispatcher state machine
  (verified by reading `inWaitForSecondStrokeState`/`inSecondStrokeInProgressState`) but this container
  cannot execute the monorepo test suite; expect to run the Bazel target above before merging anything.
- **Exception handling**: the walk deliberately matches the existing hatch — a throwing provider propagates
  out of `dispatchKeyEvent`. If reviewers prefer, wrap the provider call in a `LOG.error` guard.
- **Gating**: the walk could additionally be gated by a Registry key (`keymap.skip.dispatcher.ancestor.lookup`)
  if a kill switch is wanted for the experimental phase; left out to keep the prototype minimal, since the
  inert-by-construction argument covers the default behavior.
- **`KEY_TYPED` for claimed presses** is intentionally *not* suppressed here: the dispatcher cannot know
  which typed event corresponds to a claimed press without replicating `ignoreNextKeyTypedEvent` state for a
  path it never processed, and wrongly swallowing typed events is worse than the leak. The embedder owns it.
- The interface takes the focus owner as a parameter (unlike `KeyboardAwareFocusOwner`) so a single provider
  can distinguish descendants; if reviewers prefer exact symmetry, dropping the parameter is a one-line change.
