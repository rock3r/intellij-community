// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(ExperimentalSpectreAgentApi::class)

package org.jetbrains.jewel.bridge.e2e

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.AdditionalModulesForDevBuildServer
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.community.IdeaCommunity
import dev.sebastiano.spectre.agent.AgentAttach
import dev.sebastiano.spectre.agent.AttachOptions
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.jetbrains.jewel.e2e.PopupAutomator
import org.jetbrains.jewel.e2e.PopupScenarios
import org.jetbrains.jewel.e2e.PopupTestTags
import org.junit.jupiter.api.Test

/**
 * Runs the shared popup scenarios against Jewel as the IDE hosts it, so `JBPopupRenderer` is the renderer under test.
 *
 * Nothing cheaper reaches it. It builds a real `JBPopup` through `JBPopupFactory`, so it needs a running application
 * and a display, and before this lane existed reverting the whole file left every test that compiles the bridge module
 * green.
 *
 * The IDE is a child process: the starter builds one from sources and launches it, and Spectre attaches to it over the
 * JDK Attach API. This test JVM stays headless, which is what lets it be an ordinary `jps_test`. Attaching also keeps
 * Spectre out of the IDE — the agent's inject bootstrap carries what it needs into the target, so no Jewel artifact
 * ever ships it.
 *
 * The driver is here only to boot the IDE and open the showcase dialog by action id. Every interaction with Compose
 * content goes through Spectre, which addresses nodes from the semantics tree in the target process rather than by
 * screen coordinates, and so is not subject to the driver's Compose coordinate mapping.
 */
class JewelBridgePopupE2ETest {
    @Test
    fun `the bridge popup renderer behaves`() {
        AdditionalModulesForDevBuildServer.addAdditionalModules("intellij.devkit")
        val projectDir = Files.createTempDirectory("jewel-bridge-e2e")
        projectDir.resolve(".idea").createDirectories()

        val ideInfo = IdeInfo.IdeaCommunity.copy(platformPrefix = "community")
        val context = Starter.newContext("jewel-bridge-e2e", TestCase(ideInfo, LocalProjectInfo(projectDir)))
        context.applyVMOptionsPatch {
            // The renderer under test. Without this Jewel uses Compose's own popups, which the standalone lane
            // already compares against.
            addSystemProperty("jewel.customPopupRender", "true")
            // JEP 451: without it a JDK 21+ target warns on dynamic agent loading, and a later JDK may refuse it.
            addLine("-XX:+EnableDynamicAgentLoading", "-XX:+EnableDynamicAgentLoading")
        }

        val failures = mutableListOf<String>()
        val run = context.runIdeWithDriver(runTimeout = 20.minutes)
        val idePid = run.process.id.toLong()

        run.useDriverAndCloseIde {
            waitForProjectOpen()
            invokeAction("JewelComponentShowcaseDialog", now = false)

            // Attaching before the dialog exists fails: the agent looks for a Compose host in the target and finds
            // none, because the IDE has not loaded Compose yet. `invokeAction` is asynchronous, so wait for the
            // Jewel panel to appear first. This only reads the component tree; the pointer targeting that the
            // driver gets wrong for Compose is never used.
            waitFor("the showcase dialog is up", 90.seconds) {
                ui.xx { byClass("JewelComposePanelWrapper") }.list().isNotEmpty()
            }

            AgentAttach.attach(idePid, AttachOptions(agentJarPath = agentRuntimeJar())).use { attached ->
                val automator = AttachedPopupAutomator(attached)
                runBlocking {
                    automator.openComboBoxesView()
                    automator.run(failures, "Escape closes a hovered ComboBox") {
                        PopupScenarios.escapeClosesHoveredComboBox(it)
                    }
                    automator.run(failures, "A ComboBox popup closes cleanly") {
                        PopupScenarios.comboBoxPopupClosesCleanly(it)
                    }
                }
            }
        }

        assertEquals(emptyList(), failures, "JBPopupRenderer misbehaved")
    }
}

/**
 * The absolute path of the loadable agent jar.
 *
 * Spectre finds it on the attacher's classpath by default, but under Bazel that entry is a runfiles-relative path, and
 * `VirtualMachine.loadAgent` resolves paths in the *target* process, which has a different working directory. It has to
 * be made absolute here or the IDE reports the jar as missing.
 */
private fun agentRuntimeJar(): Path {
    val entry =
        System.getProperty("java.class.path").split(File.pathSeparator).firstOrNull {
            it.contains("spectre-agent-runtime")
        } ?: error("spectre-agent-runtime is not on the test classpath; the attacher cannot inject the agent")
    return Path.of(entry).toAbsolutePath().normalize()
}

/** The showcase opens on its first view, so the combo boxes have to be navigated to. */
private suspend fun AttachedPopupAutomator.openComboBoxesView() {
    clickByContentDescription("Show Combo Boxes")
    waitForNode(PopupTestTags.COMBO_BOX)
}

/**
 * Runs one scenario, isolated: one that fails or throws must neither stop the others nor leave a popup behind for the
 * next one to trip over. Cleanup closes any leftover popup by clicking its owner, not by pressing Escape, which is
 * inert under precisely the fault these scenarios exist to catch.
 */
private suspend fun AttachedPopupAutomator.run(
    failures: MutableList<String>,
    name: String,
    scenario: suspend (PopupAutomator) -> String?,
) {
    val outcome = runCatching { scenario(this) }
    val failure =
        outcome.getOrNull() ?: outcome.exceptionOrNull()?.let { "threw ${it::class.simpleName}: ${it.message}" }
    if (failure != null) failures += "$name: $failure"

    runCatching {
        if (isPresent(PopupTestTags.COMBO_BOX_POPUP)) {
            click(PopupTestTags.COMBO_BOX)
            waitUntilGone(PopupTestTags.COMBO_BOX_POPUP)
        }
    }
}
