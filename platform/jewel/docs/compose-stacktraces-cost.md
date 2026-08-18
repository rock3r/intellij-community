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
| After Showcase is already open | Toggle call ≈ 0; next recomposition of existing groups does **not** record | **No** for already-inserted groups unless the composition is **re-inserted** (see debug toggle below). Close/reopen still works. |
| Mode `GroupKeys` instead | Essentially no composition-time cost | Yes, but group-key traces only (no file/line from source info) |

So: **enabling later is not free for new or re-created compositions**, and it does **not**
retroactively annotate UI that is already in the slot table. If the product requirement is
“turn it on when a user hits a Jewel crash,” `SourceInformation` is the wrong mode unless
you also force a **re-insert** (not a skip-path recompose). `GroupKeys` is the mode designed
for “no overhead until crash.” Recipes for forcing insert from a debug toggle are in
[Debug toggle: force active compositions](#2-debug-toggle-force-active-compositions-to-start-recording-sourceinformation).

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

## GroupKeys mapping files

The opaque frames (`at $$compose.m$-696222513(SourceFile:1)`) are **not** a kotlinc output.
Gradle’s `includeComposeMappingFile` (Kotlin 2.3.0+, default on) is a **post-compile** AGP
task, not a Compose compiler `plugin_options` flag. It:

1. Walks compiled `.class` files / jars with `org.jetbrains.kotlin:compose-group-mapping`
   (`ComposeMapping`).
2. Reads group-key integer literals already in bytecode (`startRestartGroup` /
   `startReplaceGroup` / …) plus **line numbers** from the LineNumberTable.
3. Writes ProGuard mapping of the form:

   ```
   ComposeStackTrace -> $$compose:
       1:1:void org.jetbrains.jewel.ui.component.ButtonKt.DefaultButton(...):213:213 -> m$-696222513
   ```

4. Appends that to R8’s `mapping.txt` — **only for minified Android application variants**.

There is no kotlinc option like `composeMappingFile=/path`. Desktop/IDE builds never get
this file today because they are not R8-minified. `@FunctionKeyMeta` /
`generateFunctionKeyMetaAnnotations` is a different tooling/hot-reload artifact; the
official mapper does not need it.

### Same compilers + same commit ⇒ same mapping?

**Same shipped bytecode ⇒ same mapping.** Generate the file from the **release jars**, not
from a second kotlinc run.

Group keys are `hashCode()` of a durable path string (`fun-<signature>` plus nested path
parts). Same Kotlin + Compose compiler and the same sources will match in practice. It is
not whitespace-insensitive: inner group keys and mapping **line numbers** can move if
source offsets shift. A compiler version bump or a Compose feature flag
(`OptimizeNonSkippingGroups`, …) can change which groups exist. Pin the mapping to
**build number**, not git SHA alone.

Compose stdlib frames (`Column`, `Layout`, …) come from **those libraries’** bytecode.
Scan the bundled Compose jars too, or those frames stay as `$$compose.m$<int>`.

### Release artifact, including Studio-from-IJPL-jars

Because the mapper is a bytecode scan, **Android Studio can produce the mapping entirely
from the IJPL (and Studio) jars it already ships.** No IJPL Bazel genrule is required for
Studio to retrace GroupKeys. Scan at Studio release cut:

- IJPL Jewel + Compose runtime/foundation/ui jars for that IJPL commit
- Studio follow-up Compose/Jewel jars (including vendored-as-jar modules)

Store the result as Studio plugin resources keyed by build, and/or next to the build on
TeamCity for offline `retrace`. The IDE does not need R8; a HashMap from `m$<key>` →
FQN + line is enough.

## Production shape: IJPL vs Android Studio

Keep IJPL small: mapping files, GroupKeys default, compiler flags, and the debug
toggle stay in Studio. The one IJPL sliver worth taking is a generic throwable-decorator
EP so the error UI can show retraced Compose frames.

| Piece | Where | Why |
| --- | --- | --- |
| `Composer.setDiagnosticStackTraceMode(GroupKeys)` at startup | **Studio** (and IU/Community only if those products want wild-crash traces too) | Process-wide Compose runtime API. A Studio `StartupActivity` is enough for Android Studio. |
| GroupKeys mapping files | **Studio** release scan of product jars | Post-compile; no kotlinc flag. Includes IJPL + Studio + vendored jars. |
| In-process GroupKeys → FQN mapper | **Studio** retrace + a tiny **IJPL hook** | See below. Mapping files stay in Studio; IJPL only needs a place to run the decorator. |
| `sourceInformation=true` on Jewel / platform Compose | **IJPL compile** (Bazel `plugin_options`) | Studio cannot invent markers in already-compiled IJPL jars. Needed only if the debug toggle should show Jewel file/line, not just GroupKeys. Cheap: +2.2% class bytes, kotlinc wall clock in the noise. |
| `sourceInformation=true` on Studio Compose modules | **Studio compile** | Same flag for Studio-owned `@Composable`s. |
| Debug “record SourceInformation” toggle | **Studio** action (optional tiny IJPL restart helper) | Must not be the production default. |
| Force existing surfaces to start recording | **Studio** `simulateHotReload` (optional IJPL `key(epoch)`) | `Recomposer` invalidate is not enough. See below. |
| DevKit `ComposeVerboseStackTrace` | leave as playground | Not bundled in Community; not a product hook. |

### 1. Compose exception mapper in Studio (GroupKeys → composition data)

**Yes.** Studio can translate `$$compose.m$<key>` frames to composable FQN + line **inside
the running IDE**, using mapping files it bundled for that build. IJPL does not need to
own or understand those files.

Do **not** shell out to R8 retrace in the IDE. Parse the Compose ProGuard stanza into
`Map<Int, StackTraceElement>` at plugin load.

#### Where the error UI actually reads the stack

```text
Logger.error / JUL SEVERE
        │
        ▼
 DialogAppender          other fatals (e.g. IdeaFreezeReporter)
        │                         │
        └──────────┬──────────────┘
                   ▼
         LogMessage(throwable)     ← interned here
                   ▼
         MessagePool.addErrorMessage
                   ▼
     balloon + IdeErrorsDialog     ← user sees getThrowableText()
                   ▼
     ErrorReportSubmitter.submit   ← only if the user clicks Report
```

`com.intellij.errorHandler` (`ErrorReportSubmitter`) is **too late**: it runs on submit,
so the balloon and the dialog still show `$$compose.m$<int>` unless something earlier
rewrote the throwable.

Studio still writes the retrace in every design. The only question is **where IJPL lets
that retrace run**.

#### JUL handler (zero IJPL, stopgap)

Studio installs a `java.util.logging.Handler` on the root logger. On `SEVERE` records
that carry a throwable, it walks `cause` + `suppressed`, finds
`DiagnosticComposeException` / `$$compose` frames, and **mutates `stackTrace` in place**.
`DialogAppender` is itself a JUL handler; if ours runs first, it sees mapped frames and
the balloon/dialog/submit all look human.

Why this is a hack, not the product shape:

- Handler order vs `DialogAppender` is not a platform contract.
- Not every fatal goes through JUL. `MessagePool.addErrorMessage` is also called
  directly (freeze reporter, unhandled-exception paths). Those skip the handler.
- `LogMessage` **interns** the throwable. Mutating a live exception that Compose or
  clustering may still hold is hostile (identity, hashes, later frames).
- Idea logs would show mapped text only if the handler runs before the log formatter.

Use this only if we cannot land an IJPL change in time.

#### First-class IJPL hook (do this)

Studio implements the retrace **anyway**. A first-class hook does not duplicate that
work; it is the one-line call site so the retrace runs at the error-UI boundary
instead of on the JUL bus.

Add a small EP (name TBD; conceptually `ThrowableDecorator` /
`DiagnosticStackTraceDecorator`):

```kotlin
fun decorate(throwable: Throwable): Throwable
```

Call it **once**, in `LogMessage`’s constructor, *before* `ThrowableInterner.intern`.
That covers DialogAppender, freeze reports, and anything else that builds a
`LogMessage`. Studio’s implementation looks up `$$compose.m$<key>` and returns a
throwable whose suppressed Compose frames are rewritten (copy + `setStackTrace`, or a
second suppressed “retraced” exception — do not mutate the interned original).

Why this belongs in IJPL even though Studio owns Compose mapping:

- It is ~10 lines in platform and no Compose dependency.
- The mapper is product-specific (Studio has the files; IU might later). The *hook*
  is generic: any product can decorate fatals before they hit the error UI.
- IU/Community/Rider with Jewel get the same seam without a second JUL hack.
- We keep mapping files, GroupKeys default, and compiler flags out of IJPL.

`MessagePoolAdvisor` already sees messages before they enter the pool, but it is
internal, meant for filter/observe, and runs **after** intern. Do not overload it.

**What IJPL must still do for this path:** the decorator EP + the `LogMessage` call.
Not mapping files. Enabling GroupKeys in IJPL too is only for Community/IU wild dumps.

**What you get back.** GroupKeys traces are still less precise than SourceInformation:
first line of the `@Composable` (or group), no inline / non-Unit composables, no column.
After mapping, a frame looks like a normal `at org.jetbrains.jewel…DefaultButton(Button.kt:213)`
instead of `at $$compose.m$-696222513(SourceFile:1)`.

**Vendored-as-jar Studio modules.** Same scan. Jars are not obfuscated, so the mapping is
an index of information already in the class files (method names + LineNumberTable), not
a new leak of product logic. It only names **composable UI** (Jewel, Compose, Studio UI
composables). Agreed this is a minor info-disclosure: it makes composition structure
easier to read without decompiling. Mitigations if needed: ship mappings only in EAP /
internal builds, or keep them in a non-unpacked plugin resource. For a mapper that runs
in the user’s IDE, the file has to be on disk in the install.

### 2. Debug toggle: force active compositions to start recording SourceInformation

Flipping `Composer.setDiagnosticStackTraceMode(SourceInformation)` is ~0.001 ms and **does
not backfill** the slot table. Recording is literally:

```kotlin
if (inserting && sourceMarkersEnabled) {
    writer.recordGroupSourceInformation(sourceInformation)
}
```

The compiled `sourceInformation(...)` calls still run on a skip/recompose; the **write**
does not. `Recomposer.runningRecomposers` is a **read-only** monitor (`RecomposerInfo`
has `state` / `hasPendingWork` / `changeCount`, no invalidate). `invalidateAll`,
`invalidateGroupsWithKey`, and `forceRecomposeScopes` mark scopes dirty so the next
frame recomposes them. Existing groups stay in the slot table, so `inserting` is false
and source info is still missing. That is why injecting `key(token)` was on the table:
changing a `key` forces leave + re-enter (insert) without needing Composer to grow a
new API.

**Do not reflect composable lambdas.** The runtime already holds each root
`composition.composable`. The way to use that without touching AWT is the hot-reload
path.

**Preferred: `simulateHotReload`.** Public in `androidx.compose.runtime`, annotated
`@TestOnly`, documented “not for use in production.” For an **internal debug toggle**
that is the right shape:

1. `Composer.setDiagnosticStackTraceMode(SourceInformation)`
2. `simulateHotReload(Unit)` — for every running recomposer, `setContent {}` then
   `setContent(savedComposable)`. That is a real insert, process-wide (Jewel panels,
   raw `ComposePanel`, `ComposeWindow`, scene layers). No AWT walk, no lambda
   reflection.
3. `remember { }` / scroll / text state is reset, same as any dispose+restart. Warn
   the user.
4. Side effect: it sets `_hotReloadEnabled`, which makes
   `collectingCallByInformation` true (Layout Inspector–like). Call
   `disableHotReloadMode()` afterwards if we do not want that left on. Source-info
   recording itself is gated on `ComposeStackTraceMode.SourceInformation`, not on
   hot-reload mode; the re-insert is what fills the slot table.

`@TestOnly` is a lint, not an ABI wall; the function is in the Compose runtime jar
we already ship. Treat it as tooling, same family as Live Edit. If Compose ever
hides it, fall back to close/reopen copy.

**What not to do**

- `Composer` / `Recomposer` invalidate, `Recomposer.cancel()`, or
  `RecomposerInfo.observe` — no insert.
- `ComposePanel.dispose()` while attached — tears down `_composeContainer` (the KDoc
  “otherwise nothing will happen” is stale) and leaves a blank panel until recreate.
- Reflect `_composeContent` + wrap in `key(token)` — works, but the hot-reload path
  already does the equivalent with the lambda the composition owns.
- Detach/reattach (`isDisposeOnRemove`) — fights IntelliJ toolwindows that keep
  composition state across `removeNotify`.

**Optional IJPL nicety:** `key(epoch)` in `JewelComposePanel` if we want a
non-`@TestOnly` Jewel-only restart. Not needed if Studio is willing to call
`simulateHotReload` from the debug action.

**Tell the user** if a surface still looks stale: close and reopen that Compose UI.
The toggle still needs **`sourceInformation=true` in the jars that should appear in
the trace.** Without that, a full hot-reload restart still only shows inlined
Compose-stdlib frames plus GroupKeys.

## Recommendations

1. **Production default: `GroupKeys` in Studio, mapping bundled as a Studio release
   artifact scanned from product jars.** Zero composition-time cost; dumps are integers
   until the in-process mapper (or offline retrace) runs. Do this in Studio even if IJPL
   never emits a mapping file of its own.
2. **Do not leave `SourceInformation` on in production processes.** Official guidance plus
   insert-path recording. Treat it as a debug/internal toggle.
3. **IJPL compile flag only if the debug toggle should name Jewel file/line.**
   `sourceInformation=true` on Jewel (+ platform Compose that Studio cannot rebuild).
   +2.2% class bytes; kotlinc wall clock in the noise. Residual cost with mode `None`
   is extra JVM calls that do not write the slot table.
4. **Late SourceInformation toggle is cheap to flip and useless until insert.** Do not
   use `Recomposer` invalidate. Prefer `simulateHotReload` after flipping the mode (uses
   the composable the composition already holds; no lambda reflection). Fall back to
   “close and reopen this Compose UI.” Optional IJPL `key(epoch)` only if we refuse
   `@TestOnly`.
5. **Mapper retrace lives in Studio; the hook should be first-class IJPL.** Call a tiny
   `ThrowableDecorator` EP from `LogMessage` before intern. A JUL handler is only a
   stopgap. `ErrorReportSubmitter` is too late for the balloon/dialog. Mapping discloses
   composable FQNs already present in unobfuscated UI jars.
6. **DevKit is required for the Showcase playground**, not for the runtime API. The mode
   is a Compose runtime global.

## Implementation notes for this experiment

- `platform/jewel/scripts/measure-compose-stacktraces.py` — compile timing + constant-pool
  scan (`sourceInformation` vs `sourceInformationMarkerStart` vs own-file `.kt#`).
- `platform/jewel/scripts/enable-compose-source-information.py` — patches Jewel/DevKit
  `BUILD.bazel` `plugin_options`. Left uncommitted; run it to reproduce the “on” column.
- Tests: `ComposeStackTraceCostTest`, `ComposeStackTraceInIdeCostTest`.
- Spectre was not used: it drives a desktop UI, it does not isolate composition.
- Android modules were cloned with `./getPlugins.sh --shallow` so Bazel analysis could load
  the JPS model (`android/` is gitignored).
- This document is the experiment report plus the production-shape notes above. No
  production runtime or compiler flag is enabled on this branch.
