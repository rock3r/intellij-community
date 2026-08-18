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

This checkout needs the Android tree (`./getPlugins.sh --shallow`) so the JPS/Bazel
model can load. Compile (from repo root):

```bash
python3 platform/jewel/scripts/measure-compose-stacktraces.py off 2
python3 platform/jewel/scripts/enable-compose-source-information.py
python3 platform/jewel/scripts/measure-compose-stacktraces.py on 2
```

The enable script patches Jewel + `intellij.devkit.compose` `BUILD.bazel` files only.
Do not commit those patches unless the product decision is to ship the compiler flag.

Standalone / in-IDE runtime (targeted `jps_test`, needs `DISPLAY`):

```bash
bash bazel.cmd test //platform/jewel/ui-tests:ui-tests_test \
  --test_filter=org.jetbrains.jewel.ui.ComposeStackTraceCostTest \
  --test_env=DISPLAY=:1 --test_output=all

bash bazel.cmd test //platform/jewel/ide-laf-bridge:ideLafBridge-tests_test \
  --test_filter=org.jetbrains.jewel.bridge.ComposeStackTraceInIdeCostTest \
  --test_env=DISPLAY=:1 --test_output=all
```

`./tests.cmd --module …` also works but builds the full Ultimate test runner.

JSON lands in `out/compose-stacktraces/`. DefaultButton trees never go idle (infinite
animations), so the tests freeze `mainClock.autoAdvance` and wait on a `SideEffect`
token instead of `waitForIdle`.

## Numbers

Collected on this agent (Linux, Bazel `jvm-fastbuild` / `k8-fastbuild`, Compose runtime
1.12.x). Compile timings are a full local kotlinc of the seven targets with
`--nouse_action_cache --disk_cache=` (the JetBrains disk cache otherwise finishes in ~2s
without rerunning kotlinc).

### Compile time and bytecode

Naive UTF-8 `sourceInformation` over-counts method names and inlined Compose library
markers. The useful metrics are exact constant-pool UTF-8 `sourceInformation` (the
diagnostic API) and encoded `.kt#` strings whose file matches the class’s own source.

| Config | Full local kotlinc (s) | Class files | Class bytes | `sourceInformation` methods | Own-file `.kt#` | Library `.kt#` |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| off (current Bazel) | 85.7 | 1657 | 12,732,980 | 3 | 0 | 573 |
| on (`sourceInformation=true`) | 83.7 | 1657 | 13,017,597 | 226 | 1343 | 573 |

Delta: **class bytes +2.2%**. Own-file source-info strings go from **none to 1343**.
Library `.kt#` strings (Row, Layout, CompositionLocal, …) stay at 573 — those come from
inlined Compose stdlib that was already compiled with markers.

Wall-clock kotlinc of these modules did **not** move outside noise (~85s either way).
The compile-time cost of the flag, at this granularity, is bytecode size / extra IR
calls, not a multi-second build regression.

`ui.jar` is most of the delta: 5.74MB → 5.88MB, 0 → 636 own-file `.kt#`, 0 → 105
`sourceInformation` methods.

### Are traces actually collected?

**Only when both sides are on.** Evidence from Compose’s “Error was captured in composition”
log (the test JVM catch does not keep `DiagnosticComposeException` as a suppressed on the
rethrown `IllegalStateException`; the log is the reliable signal).

| Compile flag | Runtime mode | Jewel file/line in diagnostic stack? |
| --- | --- | --- |
| off | None | No |
| off | SourceInformation | **No Jewel frames.** At most library groups (`Column`, `Layout`, `ReusableComposeNode`) from inlined stdlib markers |
| on | None | No (markers exist in bytecode, runtime does not record/attach) |
| on | SourceInformation | **Yes.** Standalone: `ThrowingTree(ComposeStackTraceCostTest.kt:213)` plus `IntUiTheme`. In-IDE: `InIdeThrowingTree(ComposeStackTraceInIdeCostTest.kt:226)` plus `SwingBridgeTheme` |

Today’s Bazel Jewel **cannot** produce useful SourceInformation traces without the
compiler option. Turning the DevKit action on against current binaries only reconstructs
Compose-library groups.

### Standalone runtime (Jewel `IntUiTheme`, 12×8 DefaultButton tree)

First-composition medians in one process are still warmup-ordered (None is measured first,
then SourceInformation, then GroupKeys) even after a discarded warmup frame. Treat them as
noisy. **Recomposition** (no slot-table insert) is the comparable number.

**Compile flag off**

| Metric | None | SourceInformation | GroupKeys |
| --- | ---: | ---: | ---: |
| First composition median (ms) | 35.5 | 18.8 | 15.9 |
| Recomposition median (ms) | 9.0 | 8.3 | — |
| Jewel diagnostic frames on throw? | no | no | — |

Toggle: `setDiagnosticStackTraceMode` **0.001 ms**; recompose existing tree 8.0 ms;
recreate composition 45.4 ms (one noisy sample).

**Compile flag on**

| Metric | None | SourceInformation | GroupKeys |
| --- | ---: | ---: | ---: |
| First composition median (ms) | 36.1 | 18.3 | 16.4 |
| Recomposition median (ms) | 9.3 | 8.5 | — |
| Jewel diagnostic frames on throw? | no | **yes** (`ThrowingTree.kt`) | — |

Toggle: enable call **0.001 ms**; recompose existing tree 15.3 ms; recreate 16.0 ms.

Recomposition with SourceInformation is not slower than None in either compile config.
That matches the runtime: recording is paid on **insert**, not on skip/recompose.

### In-IDE runtime (`SwingBridgeTheme` / Test Application)

Same 12×8 tree. Same warmup caveat on first composition.

**Compile flag off:** recompose None 9.6 ms vs SourceInformation 8.1 ms. No Jewel frames.

**Compile flag on:** recompose None 9.9 ms vs SourceInformation 9.1 ms. Jewel frames
present (`InIdeThrowingTree` + `SwingBridgeTheme`). Enable call 0.001 ms; recreate 17.7 ms.

In-IDE and standalone agree: **mode None vs SourceInformation is lost in the noise for
this tree when not inserting**; collection quality is entirely a compile-flag question.

### Starter / released Community + DevKit

Not rerun here. Released Community binaries do **not** include this checkout’s
`sourceInformation=true` flag, so Showcase-open time would only measure the runtime toggle
against stock markers (library groups, not Jewel file/line). Use the in-IDE test above for
the `compose {}` / `SwingBridgeTheme` path on this tree.

## Recommendations

1. **Do not leave `SourceInformation` on in production Community/IU processes.** Official
   guidance plus insert-path recording. Treat it as a debug/internal tool.
2. **If the product want is “better Jewel crashes in the wild,” prefer `GroupKeys`.** Zero
   composition-time overhead by design; cost is paid only when attaching a trace after a
   throw. Precision is worse (no file/line from source info).
3. **A late IDE toggle of `SourceInformation` is cheap to flip (0.001 ms) and useless
   until insert.** Existing Jewel surfaces need close/reopen (new composition) after the
   toggle. `GroupKeys` is the mode designed for “no overhead until crash.”
4. **Compile-time markers are the Bazel gap vs Gradle, and they are cheap enough to
   consider.** Enabling `sourceInformation=true` on Jewel + `intellij.devkit.compose`
   is what makes SourceInformation traces possible. Wall-clock kotlinc did not regress
   beyond noise; class files grew ~2%. Residual runtime cost with mode still `None` is
   extra JVM calls that do not write the slot table.
5. **DevKit is required for the Showcase playground**, not for the runtime API. The mode is
   a Compose runtime global.

## Implementation notes for this experiment

- `platform/jewel/scripts/measure-compose-stacktraces.py` — compile timing + constant-pool
  scan (`sourceInformation` vs `sourceInformationMarkerStart` vs own-file `.kt#`).
- `platform/jewel/scripts/enable-compose-source-information.py` — patches Jewel/DevKit
  `BUILD.bazel` `plugin_options`. Left uncommitted; run it to reproduce the “on” column.
- Tests: `ComposeStackTraceCostTest`, `ComposeStackTraceInIdeCostTest`.
- Spectre was not used: it drives a desktop UI, it does not isolate composition.
- Android modules were cloned with `./getPlugins.sh --shallow` so Bazel analysis could load
  the JPS model (`android/` is gitignored).
