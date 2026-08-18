# Compose diagnostic stack traces: how they work, and what they cost

This is an experiment report for enabling Compose composable stack traces in Jewel
(standalone and in-IDE) under **Bazel**, not Gradle.

Pinned checkout: see the branch `cursor/compose-stacktraces-cost-3f13`. Numbers below were
collected on this machine; re-run the commands in each section to reproduce.

## What “both sides” means

Composable stack traces are **not** a single switch. Two independent pieces must line up:

1. **Compile time — marker generation.** The Compose compiler plugin emits
   `composer.sourceInformation(...)` / `sourceInformationMarkerStart/End` calls into each
   `@Composable`. In Gradle this is `composeCompiler { includeSourceInformation = true }`,
   which becomes
   `-P plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true`.
2. **Runtime — recording and attach.** `Composer.setDiagnosticStackTraceMode(...)` is a
   **process-wide** flag (default `None`). It decides whether those compiled calls actually
   write into the slot table, and whether a crash walks that table and attaches a suppressed
   `DiagnosticComposeException`.

Pavel’s Slack summary is accurate: **both** are required for the useful
`SourceInformation` traces. The runtime call alone cannot invent markers that were never
compiled in.

### Current Bazel default in this repo

Jewel Bazel targets do **not** pass `sourceInformation=true` today. The plugin option
defaults to **off** (`CompilerConfiguration.getBoolean` → `false`). Typical Gradle JVM/Android
debug builds often enable it via the Compose compiler Gradle plugin; our Bazel path does not.

The only existing runtime hook in the IDE is the internal DevKit action
`ComposeVerboseStackTrace`
(`plugins/devkit/intellij.devkit.compose/src/ComposeVerboseStackTraceAction.kt`). It starts
from `None` and toggles `SourceInformation`. Plugin DevKit is **not** bundled in Community by
default; the Showcase dialog lives in that plugin.

## Runtime modes

From Compose runtime `1.12.0-rc01` (`ComposeStackTraceMode`):

| Mode | `stackTraceEnabled` | `collectingSourceInformation` | What you get |
| --- | --- | --- | --- |
| `None` (default) | no | no | No suppressed compose trace |
| `GroupKeys` | yes | no | Cheap post-crash reconstruction from group keys already in the slot table. Less precise. **No extra work during composition.** |
| `SourceInformation` | yes | yes | Records source-info strings into the slot table **while inserting**. On crash, walks the table and attaches a suppressed exception with composable file/function/line. Official docs: cost “similar to attaching the Layout Inspector”; do not leave on in unminified release. |
| `Auto` | — | — | `GroupKeys` if the build is minified, otherwise `None`. **Not** `SourceInformation`. |

`Recomposer.collectingSourceInformation` is literally
`composeStackTraceMode == SourceInformation`.
`Recomposer.stackTraceEnabled` is `composeStackTraceMode != None`.

## When the cost is paid

### Compile time

Paid when `sourceInformation=true` is passed to the Compose compiler plugin.

- Extra IR lowering: a call (and a string constant) per composable group / groupless call.
- Larger class files, more work for kotlinc and for downstream dex/R8 if used.
- **Paid once per compilation**, independent of whether anyone ever turns the runtime mode on.

### Runtime, compiled markers present, mode = `None`

`GapComposer.sourceInformation` / `sourceInformationMarkerStart` are:

```kotlin
if (inserting && sourceMarkersEnabled) {
    writer.recordGroupSourceInformation(sourceInformation)
}
```

`sourceMarkersEnabled` is initialized from
`parentContext.collectingSourceInformation || parentContext.collectingCallByInformation`
and stays false in `None`.

So the compiled calls still happen (real JVM calls + boolean checks), but **nothing is
written to the slot table**. `errorContext` is created but the getter returns `null` unless
`stackTraceEnabled`. This is the “markers compiled in, runtime off” residual cost.

### Runtime, mode = `SourceInformation`

Paid on **insert** (first composition of a group, or any path that is inserting into the
slot table):

- Each compiled `sourceInformation` call records a string into the slot writer.
- The insert table is created with `collectSourceInformation()`.
- Slot table memory grows with those strings.
- `LocalCompositionErrorContext` is installed so measure/layout/draw can attach traces too.

**Not** paid on a skip/recompose that is not inserting. Recomposition of an already-inserted
tree is close to the `None` path aside from the extra compiled calls.

**Crash path** (only when something throws): walk the slot table, build frames, attach a
suppressed `DiagnosticComposeException`. That walk is post-crash; it is not a frame-to-frame
cost.

### Toggling later (registry / action / `setDiagnosticStackTraceMode`)

`Composer.setDiagnosticStackTraceMode` assigns a global `var`. The assignment itself is
effectively free.

The KDoc says changing the mode **does not affect already running compositions**. The
implementation is slightly more nuanced:

- `sourceMarkersEnabled` is snapshotted when the composer is constructed.
- `startRoot` will **upgrade** `false → true` if the parent recomposer now wants source
  information (`if (!sourceMarkersEnabled) sourceMarkersEnabled = parentContext.collectingSourceInformation`).
  It will **not** downgrade `true → false` the same way.
- Recording still only happens when `inserting`. Enabling the flag on a tree that is already
  inserted does **not** backfill source info into existing groups.
- `errorContext`’s getter re-reads `stackTraceEnabled` each time, so crash attachment can
  start looking for traces immediately — but if the slot table was filled without source
  info, `attachComposeStackTrace` has nothing useful to attach (and the API docs say it is a
  no-op when the composition contains no source information).

Practical consequence for an IDE toggle:

| When you flip it on | Cost | Will the next crash have SourceInformation traces? |
| --- | --- | --- |
| Before the Jewel surface is first composed | Subsequent **first compositions** pay the recording cost | Yes, for surfaces composed after the flip |
| After Showcase is already open | Toggle call ≈ 0; next recomposition of existing groups does **not** record | **No** for already-inserted groups. Close and reopen the dialog (new composition / insert) to start recording |
| Mode `GroupKeys` instead | Essentially no composition-time cost | Yes, but group-key traces only (no file/line from source info) |

So: **enabling later is not free for new or re-created compositions**, and it does **not**
retroactively annotate UI that is already in the slot table. If the product requirement is
“turn it on when a user hits a Jewel crash,” `SourceInformation` is the wrong mode unless
you also force a recomposition/recreate. `GroupKeys` is the mode designed for “no overhead
until crash.”

## How to collect the numbers

Compile (from repo root):

```bash
python3 platform/jewel/scripts/measure-compose-stacktraces.py off 3
python3 platform/jewel/scripts/enable-compose-source-information.py
python3 platform/jewel/scripts/measure-compose-stacktraces.py on 3
```

Standalone runtime (Jewel `IntUiTheme`, no IDE):

```bash
./tests.cmd --module intellij.platform.jewel.uiTests \
  --test org.jetbrains.jewel.ui.ComposeStackTraceCostTest
```

In-IDE composition (real `SwingBridgeTheme` / `compose {}` path, Test Application — same
bridge the Showcase dialog uses):

```bash
./tests.cmd --module intellij.platform.jewel.ideLafBridge.tests \
  --test org.jetbrains.jewel.bridge.ComposeStackTraceInIdeCostTest
```

Starter / full IDE (released Community + marketplace Plugin DevKit, **stock binaries**):

```bash
./tests.cmd --module intellij.tools.ide.starter.driver.tests \
  --test com.intellij.ide.starter.driver.ComposeStackTraceIdeStarterTest
```

JSON lands in `out/compose-stacktraces/`.

## Numbers

Filled in after the measurement runs on this machine.

### Compile time (Bazel, action cache disabled)

| Config | Median wall time | Class files with `sourceInformation` UTF-8 | UTF-8 hits | Total class bytes |
| --- | ---: | ---: | ---: | ---: |
| off (current Bazel) | _pending_ | _pending_ | _pending_ | _pending_ |
| on (`sourceInformation=true`) | _pending_ | _pending_ | _pending_ | _pending_ |

Targets: `foundation`, `ui`, `int-ui-standalone`, `ide-laf-bridge`, `showcase`, `standalone`,
`intellij.devkit.compose`.

### Standalone runtime (Jewel IntUi)

| Metric | None | SourceInformation | GroupKeys |
| --- | ---: | ---: | ---: |
| First composition median (ms) | _pending_ | _pending_ | _pending_ |
| Recomposition median (ms) | _pending_ | _pending_ | — |
| Diagnostic trace attached on throw? | _pending_ | _pending_ | — |

Toggle-after-compose:

| Step | ms / result |
| --- | --- |
| `setDiagnosticStackTraceMode(SourceInformation)` | _pending_ |
| Recompose existing tree | _pending_ |
| Recreate composition | _pending_ |
| Throw after toggle (new composition) | _pending_ |

### In-IDE runtime (`SwingBridgeTheme`)

Same table, `_pending_` until `ComposeStackTraceInIdeCostTest` runs.

### Starter / released Community + DevKit

Dialog-open time is **not** a pure composition microbenchmark (IDE startup, plugin load,
Swing dialog). It answers “does a user-visible toggle change Showcase open time on stock
bits?”

| Showcase open | ms |
| --- | ---: |
| runtime off | _pending_ |
| runtime on after toggle | _pending_ |

## Recommendations

1. **Do not leave `SourceInformation` on in production Community/IU processes.** Official
   guidance plus the insert-path recording cost. Treat it as a debug/internal tool.
2. **If the product want is “better Jewel crashes in the wild,” prefer `GroupKeys`.** It is
   designed for zero composition-time overhead; cost is paid only when attaching a trace
   after a throw. Precision is worse (no file/line from source info).
3. **A late IDE toggle of `SourceInformation` is cheap to flip and expensive to make useful.**
   Existing Jewel surfaces will not gain source-info traces until they are recomposed with
   insert (typically close/reopen). Tell users that, or recreate the composition when the
   toggle turns on.
4. **Compile-time markers are the Bazel gap vs Gradle.** Enabling
   `sourceInformation=true` on Jewel + `intellij.devkit.compose` is what makes
   `SourceInformation` traces possible at all. That compile cost is paid by everyone who
   builds those modules, even if the runtime mode stays `None`. Measure the table above
   before turning it on for all Bazel builds.
5. **DevKit is required for the Showcase playground**, not for the runtime API. The mode is
   a Compose runtime global; any in-process code can call it. The Showcase is just a dense
   Jewel tree to exercise.

## Implementation notes for this experiment

- `platform/jewel/scripts/measure-compose-stacktraces.py` — compile timing + bytecode scan.
- `platform/jewel/scripts/enable-compose-source-information.py` — patches Jewel/DevKit
  `BUILD.bazel` `plugin_options`.
- Tests write JSON under `out/compose-stacktraces/`.
- Spectre was not used for the cost numbers: it drives and records a desktop UI, it does not
  isolate composition. Compose UI Test `waitForIdle` + `nanoTime` is the right tool for
  first-composition / recomposition. Starter + remote driver is used for the full-IDE
  Showcase path.
