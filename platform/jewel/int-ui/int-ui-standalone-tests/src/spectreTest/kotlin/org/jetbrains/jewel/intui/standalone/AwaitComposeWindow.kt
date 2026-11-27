// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.intui.standalone

import androidx.compose.ui.awt.ComposeWindow
import java.awt.Container
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * Waits until a test application has published its window and the window is showing on screen.
 *
 * The first window of a JVM pays for JVM startup, Skiko's native loading and the GL probe with its fallback. On a cold
 * CI runner that has taken longer than ten seconds (JEWEL-1441). Waiting for `isShowing` ensures synthetic input does
 * not race the initial window display (JEWEL-1463).
 */
internal suspend fun AtomicReference<ComposeWindow?>.awaitWindow(timeout: Duration = 60.seconds): ComposeWindow {
    val start = TimeSource.Monotonic.markNow()
    var dumpedSlow = false
    while (start.elapsedNow() < timeout) {
        val candidate = get()
        if (candidate != null && candidate.isShowingOnEdt()) {
            return candidate
        }
        if (!dumpedSlow && start.elapsedNow() >= 15.seconds) {
            dumpedSlow = true
            val sb = StringBuilder("=== DIAG SLOW awaitWindow (>15s) candidate=${candidate != null} ===\n")
            for ((t, stack) in Thread.getAllStackTraces().entries.sortedBy { it.key.name }) {
                if (
                    t.name.contains("skiko", true) ||
                        t.name.contains("AWT", true) ||
                        t.name.contains("spectre", true) ||
                        t.name.contains("main", true) ||
                        stack.any { it.className.contains("skiko") || it.className.contains("compose") }
                ) {
                    sb.append("  THREAD [").append(t.name).append("] state=").append(t.state).append('\n')
                    for (f in stack.take(25)) sb.append("    at ").append(f).append('\n')
                }
            }
            sb.append("=== END DIAG SLOW awaitWindow ===\n")
            print(sb.toString())
            System.out.flush()
        }
        delay(100.milliseconds)
    }
    error("The Compose test window was not showing within $timeout")
}

/** Stops a standalone test application and waits until its thread finishes and its window is disposed. */
internal fun stopApplicationWindow(
    exitApplication: AtomicReference<(() -> Unit)?>,
    window: AtomicReference<ComposeWindow?>,
    appThread: AtomicReference<Thread?>,
    timeout: Duration = 10.seconds,
) {
    val currentWindow = window.getAndSet(null)
    exitApplication.getAndSet(null)?.invoke()
    appThread.getAndSet(null)?.join(timeout.inWholeMilliseconds)
    if (SwingUtilities.isEventDispatchThread()) {
        if (currentWindow != null && currentWindow.isDisplayable) {
            currentWindow.dispose()
        }
    } else {
        SwingUtilities.invokeAndWait {
            if (currentWindow != null && currentWindow.isDisplayable) {
                currentWindow.dispose()
            }
        }
    }
}

private fun ComposeWindow.isShowingOnEdt(): Boolean {
    if (SwingUtilities.isEventDispatchThread()) {
        if (isShowing) disableInvisibleComponentFocus(this)
        return isShowing
    }
    var showing = false
    SwingUtilities.invokeAndWait {
        showing = isShowing
        if (showing) disableInvisibleComponentFocus(this)
    }
    return showing
}

/**
 * Stops `ComposeSceneMediator.InvisibleComponent` from taking AWT focus during `resetFocus()` on Windows. That
 * component has no `KeyListener`, so `RobotDriver.synthetic` drops keys while it holds focus.
 */
private fun disableInvisibleComponentFocus(container: Container) {
    for (component in container.components) {
        if (component.javaClass.name.endsWith("InvisibleComponent")) {
            component.isFocusable = false
        }
        if (component is Container) {
            disableInvisibleComponentFocus(component)
        }
    }
}
