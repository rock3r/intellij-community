package com.intellij.ide.starter.driver

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.junit5.config.KillOutdatedProcessesAfterEach
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.community.IdeaCommunity
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir

/**
 * Drives a released IntelliJ Community IDE with Plugin DevKit installed (not bundled by default)
 * and opens the Jewel Components Showcase. Measures dialog-open time before and after toggling
 * [ComposeVerboseStackTrace].
 *
 * Stock Community binaries do not include this repo's compile-time `sourceInformation` flag.
 * Compile-flag cost is measured separately against Bazel Jewel targets in this checkout.
 */
@ExtendWith(KillOutdatedProcessesAfterEach::class)
class ComposeStackTraceIdeStarterTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `showcase open cost with and without runtime stack traces`() {
    val projectDir = tempDir.resolve("empty-project")
    Files.createDirectories(projectDir)
    Files.writeString(projectDir.resolve("dummy.txt"), "compose stacktrace probe\n")

    val context =
      Starter.newContext(
          testName = CurrentTestMethod.hyphenateWithClass(),
          testCase = TestCase(IdeInfo.IdeaCommunity, LocalProjectInfo(projectDir)).useRelease(),
        )
        .skipIndicesInitialization()
        .disableReportingStatisticsToProduction()
        .applyVMOptionsPatch {
          addSystemProperty("idea.is.internal", true)
        }

    context.pluginConfigurator.installPluginFromPluginManager("DevKit", context.ide)

    var withoutMs = -1L
    var withMs = -1L

    context.runIdeWithDriver(runTimeout = 15.minutes, launchName = "compose-stacktraces").useDriverAndCloseIde {
      waitForProjectOpen(3.minutes)
      waitForIndicators(2.minutes)

      withoutMs = openShowcaseAndTime()
      invokeAction("ComposeVerboseStackTrace", now = true)
      withMs = openShowcaseAndTime()
    }

    val report =
      """
      {
        "scenario": "ide-starter-community-devkit-showcase",
        "showcaseOpenMs": {
          "runtimeOff": $withoutMs,
          "runtimeOnAfterToggle": $withMs
        }
      }
      """.trimIndent()

    val workspace = System.getenv("BUILD_WORKSPACE_DIRECTORY") ?: System.getProperty("user.dir")
    val dir = Path.of(workspace, "out", "compose-stacktraces")
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("ide-starter-runtime.json"), report)
    println(report)
  }

  private fun Driver.openShowcaseAndTime(): Long {
    val start = System.nanoTime()
    invokeAction("JewelComponentShowcaseDialog", now = false)
    ui.x { byAccessibleName("Jewel Components Showcase") }.waitFound(30.seconds)
    val elapsed = (System.nanoTime() - start) / 1_000_000
    try {
      ui.keyboard { key(KeyEvent.VK_ESCAPE) }
    }
    catch (_: Throwable) {
      // keep going; the next open still produces a comparable sample
    }
    return elapsed
  }
}
