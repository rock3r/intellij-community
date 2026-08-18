// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Measures Jewel standalone composition cost and whether Compose diagnostic stack traces are
 * actually attached. Results are written to `out/compose-stacktraces/standalone-runtime.json`.
 */
class ComposeStackTraceCostTest {
    @get:Rule val rule = createComposeRule()

    private val composedToken = AtomicInteger(Int.MIN_VALUE)

    @Before
    fun freezeFrameClock() {
        rule.mainClock.autoAdvance = false
    }

    @After
    fun resetMode() {
        Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.None)
    }

    @Test
    fun `measure standalone composition cost and stacktrace collection`() {
        // Drop class-load / first-frame cost so mode comparisons are not dominated by warmup.
        setThemeContent(-1) { BenchmarkTree(-1, composedToken) }

        val firstNone = measureFirstComposition(ComposeStackTraceMode.None)
        val firstSource = measureFirstComposition(ComposeStackTraceMode.SourceInformation)
        val firstGroupKeys = measureFirstComposition(ComposeStackTraceMode.GroupKeys)

        val recomposeNone = measureRecomposition(ComposeStackTraceMode.None)
        val recomposeSource = measureRecomposition(ComposeStackTraceMode.SourceInformation)

        val toggle = measureEnableAfterFirstComposition()

        val tracesNone = inspectFailure(ComposeStackTraceMode.None)
        val tracesSource = inspectFailure(ComposeStackTraceMode.SourceInformation)
        val tracesAfterToggle = inspectFailureAfterToggle()

        val report =
            buildString {
                appendLine("{")
                appendLine("  \"scenario\": \"jewel-standalone\",")
                appendLine("  \"workload\": { \"rows\": $ROWS, \"cols\": $COLS, \"recomposeSamples\": $RECOMPOSE_SAMPLES },")
                appendLine("  \"firstCompositionMs\": {")
                appendLine("    \"none\": ${firstNone.toJson()},")
                appendLine("    \"sourceInformation\": ${firstSource.toJson()},")
                appendLine("    \"groupKeys\": ${firstGroupKeys.toJson()}")
                appendLine("  },")
                appendLine("  \"recompositionMs\": {")
                appendLine("    \"none\": ${recomposeNone.toJson()},")
                appendLine("    \"sourceInformation\": ${recomposeSource.toJson()}")
                appendLine("  },")
                appendLine("  \"enableAfterFirstComposition\": ${toggle.toJson()},")
                appendLine("  \"stackTraces\": {")
                appendLine("    \"none\": ${tracesNone.toJson()},")
                appendLine("    \"sourceInformation\": ${tracesSource.toJson()},")
                appendLine("    \"enableAfterExistingComposition\": ${tracesAfterToggle.toJson()}")
                appendLine("  }")
                appendLine("}")
            }

        writeReport("standalone-runtime.json", report)
        println(report)

        // The test always records data. Collection itself is asserted only as a property of the
        // compile+runtime configuration, not as a hard pass/fail — Bazel Jewel currently may not
        // emit source-information markers.
        check(firstNone.medianMs > 0 && firstSource.medianMs > 0) { "composition timings must be positive" }
    }

    private fun measureFirstComposition(mode: ComposeStackTraceMode): TimingStats {
        val samples = LongArray(FIRST_SAMPLES)
        repeat(FIRST_SAMPLES) { i ->
            Composer.setDiagnosticStackTraceMode(mode)
            val start = System.nanoTime()
            setThemeContent(i) { BenchmarkTree(i, composedToken) }
            samples[i] = System.nanoTime() - start
        }
        return TimingStats.fromNanos(samples)
    }

    private fun measureRecomposition(mode: ComposeStackTraceMode): TimingStats {
        Composer.setDiagnosticStackTraceMode(mode)
        val token = mutableStateOf(0)
        setThemeContent(0) { BenchmarkTree(token.value, composedToken) }

        val samples = LongArray(RECOMPOSE_SAMPLES)
        repeat(RECOMPOSE_SAMPLES) { i ->
            val next = i + 1
            val start = System.nanoTime()
            rule.runOnUiThread { token.value = next }
            rule.mainClock.advanceTimeByFrame()
            waitUntilComposed(next)
            samples[i] = System.nanoTime() - start
        }
        return TimingStats.fromNanos(samples)
    }

    private fun measureEnableAfterFirstComposition(): ToggleStats {
        Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.None)
        val token = mutableStateOf(0)
        setThemeContent(0) { BenchmarkTree(token.value, composedToken) }

        val enableStart = System.nanoTime()
        Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.SourceInformation)
        val enableNs = System.nanoTime() - enableStart

        val recomposeStart = System.nanoTime()
        rule.runOnUiThread { token.value = 1 }
        rule.mainClock.advanceTimeByFrame()
        waitUntilComposed(1)
        val recomposeAfterEnableNs = System.nanoTime() - recomposeStart

        val recreateStart = System.nanoTime()
        setThemeContent(2) { BenchmarkTree(2, composedToken) }
        val recreateNs = System.nanoTime() - recreateStart

        return ToggleStats(
            enableCallMs = enableNs / 1_000_000.0,
            recomposeExistingTreeMs = recomposeAfterEnableNs / 1_000_000.0,
            recreateCompositionMs = recreateNs / 1_000_000.0,
        )
    }

    private fun inspectFailure(mode: ComposeStackTraceMode): TraceInspection {
        Composer.setDiagnosticStackTraceMode(mode)
        val thrown =
            try {
                rule.setContent { IntUiTheme { ThrowingTree() } }
                rule.waitUntil(timeoutMillis = THROW_TIMEOUT_MS) { false }
                null
            } catch (t: Throwable) {
                t
            }
        return TraceInspection.from(thrown)
    }

    private fun inspectFailureAfterToggle(): TraceInspection {
        Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.None)
        setThemeContent(0) { BenchmarkTree(0, composedToken) }

        Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.SourceInformation)
        val thrown =
            try {
                rule.setContent { IntUiTheme { ThrowingTree() } }
                rule.waitUntil(timeoutMillis = THROW_TIMEOUT_MS) { false }
                null
            } catch (t: Throwable) {
                t
            }
        return TraceInspection.from(thrown)
    }

    private fun setThemeContent(token: Int, content: @Composable () -> Unit) {
        composedToken.set(Int.MIN_VALUE)
        rule.setContent { IntUiTheme { content() } }
        rule.mainClock.advanceTimeByFrame()
        waitUntilComposed(token)
    }

    private fun waitUntilComposed(token: Int) {
        rule.waitUntil(timeoutMillis = COMPOSE_TIMEOUT_MS) { composedToken.get() == token }
    }

    companion object {
        private const val ROWS = 12
        private const val COLS = 8
        private const val FIRST_SAMPLES = 8
        private const val RECOMPOSE_SAMPLES = 20
        private const val COMPOSE_TIMEOUT_MS = 30_000L
        private const val THROW_TIMEOUT_MS = 5_000L
    }
}

@Composable
private fun BenchmarkTree(token: Int, composedToken: AtomicInteger) {
    SideEffect { composedToken.set(token) }
    Column {
        repeat(12) { row ->
            Row {
                repeat(8) { col ->
                    DefaultButton(onClick = {}) { Text("R${row}C${col}#$token") }
                }
            }
        }
    }
}

@Composable
private fun ThrowingTree() {
    Column {
        Text("before throw")
        error("compose-stacktrace-probe")
    }
}

internal data class TimingStats(val samplesMs: List<Double>) {
    val medianMs: Double = percentile(0.50)
    val p90Ms: Double = percentile(0.90)
    val meanMs: Double = if (samplesMs.isEmpty()) 0.0 else samplesMs.average()

    private fun percentile(p: Double): Double {
        if (samplesMs.isEmpty()) return 0.0
        val sorted = samplesMs.sorted()
        val index = ((sorted.size - 1) * p).roundToLong().toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    fun toJson(): String =
        """{"median": ${medianMs.f()}, "p90": ${p90Ms.f()}, "mean": ${meanMs.f()}, "n": ${samplesMs.size}}"""

    companion object {
        fun fromNanos(samples: LongArray): TimingStats = TimingStats(samples.map { it / 1_000_000.0 })
    }
}

internal data class ToggleStats(
    val enableCallMs: Double,
    val recomposeExistingTreeMs: Double,
    val recreateCompositionMs: Double,
) {
    fun toJson(): String =
        """{"enableCallMs": ${enableCallMs.f()}, "recomposeExistingTreeMs": ${recomposeExistingTreeMs.f()}, "recreateCompositionMs": ${recreateCompositionMs.f()}}"""
}

internal data class TraceInspection(
    val threw: Boolean,
    val message: String?,
    val suppressedTypeNames: List<String>,
    val hasDiagnosticComposeException: Boolean,
    val hasCompositionStackMessage: Boolean,
    val suppressedPreview: String?,
) {
    fun toJson(): String {
        val types = suppressedTypeNames.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        return buildString {
            append("{")
            append("\"threw\": $threw, ")
            append("\"hasDiagnosticComposeException\": $hasDiagnosticComposeException, ")
            append("\"hasCompositionStackMessage\": $hasCompositionStackMessage, ")
            append("\"suppressedTypes\": $types, ")
            append("\"message\": ${message.toJsonString()}, ")
            append("\"suppressedPreview\": ${suppressedPreview.toJsonString()}")
            append("}")
        }
    }

    companion object {
        fun from(thrown: Throwable?): TraceInspection {
            if (thrown == null) {
                return TraceInspection(false, null, emptyList(), false, false, null)
            }
            val chain = generateSequence(thrown) { it.cause }.toList()
            val suppressed = chain.flatMap { it.suppressedExceptions }
            val names = suppressed.map { it.javaClass.name }
            val fullText = thrown.stackTraceToString()
            val preview = suppressed.firstOrNull()?.stackTraceToString()?.lineSequence()?.take(20)?.joinToString("\\n")
                ?: if (fullText.contains("Composition stack")) fullText.lineSequence().take(20).joinToString("\\n") else null
            return TraceInspection(
                threw = true,
                message = chain.firstNotNullOfOrNull { it.message },
                suppressedTypeNames = names,
                hasDiagnosticComposeException =
                    names.any { it.contains("DiagnosticCompose") } || fullText.contains("DiagnosticComposeException"),
                hasCompositionStackMessage = fullText.contains("Composition stack"),
                suppressedPreview = preview,
            )
        }
    }
}

internal fun writeReport(fileName: String, contents: String) {
    val dirs =
        listOfNotNull(
            System.getenv("BUILD_WORKSPACE_DIRECTORY")?.let { Path.of(it, "out", "compose-stacktraces") },
            System.getenv("TEST_UNDECLARED_OUTPUTS_DIR")?.let { Path.of(it) },
            Path.of(System.getProperty("user.dir"), "out", "compose-stacktraces"),
            Path.of("/workspace/out/compose-stacktraces"),
        )
    for (dir in dirs.distinct()) {
        runCatching {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve(fileName), contents)
        }
    }
}

internal fun Double.f(): String = "%.3f".format(this)

internal fun String?.toJsonString(): String =
    if (this == null) "null"
    else "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
