// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.intellij.testFramework.TestApplicationManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToLong
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * In-IDE Jewel path: composition runs under [SwingBridgeTheme], which is what `compose {}` uses
 * for the Jewel Showcase dialog. Results go to `out/compose-stacktraces/inide-runtime.json`.
 */
@OptIn(ExperimentalJewelApi::class)
public class ComposeStackTraceInIdeCostTest {
    @JvmField @Rule public val rule = createComposeRule()

    @Before
    public fun startIdeApplication() {
        TestApplicationManager.getInstance()
    }

    @After
    public fun resetMode() {
        Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.None)
    }

    @Test
    public fun `measure in-IDE composition cost and stacktrace collection`() {
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
                appendLine("  \"scenario\": \"jewel-in-ide-swing-bridge\",")
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

        val dir = Path.of("out", "compose-stacktraces")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("inide-runtime.json"), report)
        println(report)

        check(firstNone.medianMs > 0 && firstSource.medianMs > 0) { "composition timings must be positive" }
    }

    private fun measureFirstComposition(mode: ComposeStackTraceMode): InIdeTiming {
        val samples = LongArray(FIRST_SAMPLES)
        repeat(FIRST_SAMPLES) { i ->
            Composer.setDiagnosticStackTraceMode(mode)
            val token = mutableStateOf(i)
            val start = System.nanoTime()
            rule.setContent { SwingBridgeTheme { InIdeBenchmarkTree(token.value) } }
            rule.waitForIdle()
            samples[i] = System.nanoTime() - start
        }
        return InIdeTiming.fromNanos(samples)
    }

    private fun measureRecomposition(mode: ComposeStackTraceMode): InIdeTiming {
        Composer.setDiagnosticStackTraceMode(mode)
        val token = mutableStateOf(0)
        rule.setContent { SwingBridgeTheme { InIdeBenchmarkTree(token.value) } }
        rule.waitForIdle()

        val samples = LongArray(RECOMPOSE_SAMPLES)
        repeat(RECOMPOSE_SAMPLES) { i ->
            val start = System.nanoTime()
            rule.runOnIdle { token.value = i + 1 }
            rule.waitForIdle()
            samples[i] = System.nanoTime() - start
        }
        return InIdeTiming.fromNanos(samples)
    }

    private fun measureEnableAfterFirstComposition(): InIdeToggle {
        Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.None)
        val token = mutableStateOf(0)
        rule.setContent { SwingBridgeTheme { InIdeBenchmarkTree(token.value) } }
        rule.waitForIdle()

        val enableStart = System.nanoTime()
        Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.SourceInformation)
        val enableNs = System.nanoTime() - enableStart

        val recomposeStart = System.nanoTime()
        rule.runOnIdle { token.value = 1 }
        rule.waitForIdle()
        val recomposeAfterEnableNs = System.nanoTime() - recomposeStart

        val recreateStart = System.nanoTime()
        rule.setContent { SwingBridgeTheme { InIdeBenchmarkTree(2) } }
        rule.waitForIdle()
        val recreateNs = System.nanoTime() - recreateStart

        return InIdeToggle(
            enableCallMs = enableNs / 1_000_000.0,
            recomposeExistingTreeMs = recomposeAfterEnableNs / 1_000_000.0,
            recreateCompositionMs = recreateNs / 1_000_000.0,
        )
    }

    private fun inspectFailure(mode: ComposeStackTraceMode): InIdeTrace {
        Composer.setDiagnosticStackTraceMode(mode)
        val thrown =
            try {
                rule.setContent { SwingBridgeTheme { InIdeThrowingTree() } }
                rule.waitForIdle()
                null
            } catch (t: Throwable) {
                t
            }
        return InIdeTrace.from(thrown)
    }

    private fun inspectFailureAfterToggle(): InIdeTrace {
        Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.None)
        val token = mutableStateOf(0)
        rule.setContent { SwingBridgeTheme { InIdeBenchmarkTree(token.value) } }
        rule.waitForIdle()

        Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.SourceInformation)
        val thrown =
            try {
                rule.setContent { SwingBridgeTheme { InIdeThrowingTree() } }
                rule.waitForIdle()
                null
            } catch (t: Throwable) {
                t
            }
        return InIdeTrace.from(thrown)
    }

    private companion object {
        private const val ROWS = 12
        private const val COLS = 8
        private const val FIRST_SAMPLES = 8
        private const val RECOMPOSE_SAMPLES = 20
    }
}

@Composable
private fun InIdeBenchmarkTree(token: Int) {
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
private fun InIdeThrowingTree() {
    Column {
        Text("before throw")
        error("compose-stacktrace-probe")
    }
}

private data class InIdeTiming(val samplesMs: List<Double>) {
    val medianMs: Double = percentile(0.50)

    private fun percentile(p: Double): Double {
        if (samplesMs.isEmpty()) return 0.0
        val sorted = samplesMs.sorted()
        val index = ((sorted.size - 1) * p).roundToLong().toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    fun toJson(): String {
        val mean = if (samplesMs.isEmpty()) 0.0 else samplesMs.average()
        val p90 = percentile(0.90)
        return """{"median": ${medianMs.f()}, "p90": ${p90.f()}, "mean": ${mean.f()}, "n": ${samplesMs.size}}"""
    }

    companion object {
        fun fromNanos(samples: LongArray): InIdeTiming = InIdeTiming(samples.map { it / 1_000_000.0 })
    }
}

private data class InIdeToggle(
    val enableCallMs: Double,
    val recomposeExistingTreeMs: Double,
    val recreateCompositionMs: Double,
) {
    fun toJson(): String =
        """{"enableCallMs": ${enableCallMs.f()}, "recomposeExistingTreeMs": ${recomposeExistingTreeMs.f()}, "recreateCompositionMs": ${recreateCompositionMs.f()}}"""
}

private data class InIdeTrace(
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
            append("\"message\": ${message.json()}, ")
            append("\"suppressedPreview\": ${suppressedPreview.json()}")
            append("}")
        }
    }

    companion object {
        fun from(thrown: Throwable?): InIdeTrace {
            if (thrown == null) {
                return InIdeTrace(false, null, emptyList(), false, false, null)
            }
            val suppressed =
                thrown.suppressedExceptions + generateSequence(thrown.cause) { it.cause }.flatMap { it.suppressedExceptions }
            val names = suppressed.map { it.javaClass.name }
            val preview = suppressed.firstOrNull()?.stackTraceToString()?.lineSequence()?.take(20)?.joinToString("\\n")
            return InIdeTrace(
                threw = true,
                message = thrown.message,
                suppressedTypeNames = names,
                hasDiagnosticComposeException = names.any { it.contains("DiagnosticCompose") },
                hasCompositionStackMessage =
                    suppressed.any { it.message?.contains("Composition stack") == true } ||
                        thrown.stackTraceToString().contains("Composition stack"),
                suppressedPreview = preview,
            )
        }
    }
}

private fun Double.f(): String = "%.3f".format(this)

private fun String?.json(): String =
    if (this == null) "null"
    else "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
