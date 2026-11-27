@file:OptIn(ExperimentalJewelApi::class)

package org.jetbrains.jewel.intui.standalone.popup

// Differential test: the custom renderer must behave exactly as Compose's own popup does, for every component
// that opens a popup. Compose's renderer is the oracle here, so a divergence is a bug in JDialogRenderer even
// when both outcomes look individually reasonable.

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.application
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.FocusEvent
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.JewelFlags
import org.jetbrains.jewel.intui.standalone.awaitWindow
import org.jetbrains.jewel.intui.standalone.stopApplicationWindow
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.window.Window as JewelWindow
import org.jetbrains.jewel.ui.component.ComboBox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.EditableComboBox
import org.jetbrains.jewel.ui.component.PopupContainer
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.SpeedSearchArea
import org.jetbrains.jewel.ui.component.SpeedSearchState
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.gotit.GotItButtons
import org.jetbrains.jewel.ui.component.gotit.GotItTooltip
import org.jetbrains.jewel.ui.component.rememberSpeedSearchState
import org.jetbrains.jewel.ui.component.search.SpeedSearchableComboBox
import org.junit.jupiter.api.Test

private const val OPEN_TAG = "matrix.open"
private const val POPUP_TAG = "Jewel.ComboBox.Popup"
private const val MENU_TAG = "matrix.menuPopup"
private const val SEARCH_INPUT_TAG = "SpeedSearchArea.Input"
private const val RAW_POPUP_TAG = "parity.rawPopup"

/** Set when Escape reaches the owner window's Compose tree, i.e. nothing above it consumed the key. */
private val hostSawEscape = AtomicBoolean(false)

/**
 * Set when focus lands in the owner window's own tree.
 *
 * A popup can consume a key and still move the wrong focus, if it reads the owner window's [FocusManager] instead of
 * its own. The key probe above cannot see that, because the key never reaches the owner.
 */
private val hostGainedFocus = AtomicBoolean(false)

/**
 * The owner window, for the anchoring comparison.
 *
 * The window is kept rather than its location, because `locationOnScreen` throws until the window shows. A case reads
 * the location when it measures, by which point the window is on screen.
 */
private val hostWindow = AtomicReference<ComposeWindow?>(null)

private val activeDiag = AtomicReference<DiagCollector?>(null)

private val printedBaselinePass = AtomicBoolean(false)

private class DiagCollector(val label: String) {
    private val startNanos = System.nanoTime()
    val events = CopyOnWriteArrayList<String>()
    private val marks = CopyOnWriteArrayList<Pair<String, Long>>()
    val speedSearchState = AtomicReference<SpeedSearchState?>(null)
    val comboSawEscapePreview = AtomicBoolean(false)
    val comboSawEscapeKey = AtomicBoolean(false)
    val hostSawEscapePreview = AtomicBoolean(false)
    private var awtListener: AWTEventListener? = null

    fun elapsedMs(): Long = (System.nanoTime() - startNanos) / 1_000_000

    fun mark(name: String) {
        val ms = elapsedMs()
        marks.add(name to ms)
        events.add("[t=${ms}ms] MARK $name")
    }

    fun timingSummary(): String = marks.joinToString(" ") { "${it.first}=${it.second}ms" }

    fun log(msg: String) {
        val elapsedMs = elapsedMs()
        events.add("[t=${elapsedMs}ms] $msg")
    }

    fun installAwtListener() {
        val listener = AWTEventListener { event ->
            when (event) {
                is KeyEvent -> {
                    val idName =
                        when (event.id) {
                            KeyEvent.KEY_PRESSED -> "PRESSED"
                            KeyEvent.KEY_TYPED -> "TYPED"
                            KeyEvent.KEY_RELEASED -> "RELEASED"
                            else -> "ID_${event.id}"
                        }
                    val src = event.component
                    val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    val w = hostWindow.get()
                    log(
                        "AWT_KEY $idName vk=${event.keyCode} ch=0x${event.keyChar.code.toString(16)} " +
                            "mods=0x${event.modifiersEx.toString(16)} cons=${event.isConsumed} " +
                            "src=${describeComp(src)} kfmFO=${describeComp(kfm.focusOwner)} " +
                            "winFoc=${w?.isFocused} winFO=${describeComp(w?.focusOwner)}"
                    )
                }
                is FocusEvent -> {
                    val idName = if (event.id == FocusEvent.FOCUS_GAINED) "GAINED" else "LOST"
                    log(
                        "AWT_FOCUS $idName temp=${event.isTemporary} cause=${event.cause} " +
                            "src=${describeComp(event.component)} opp=${describeComp(event.oppositeComponent)}"
                    )
                }
                is WindowEvent -> {
                    val idName =
                        when (event.id) {
                            WindowEvent.WINDOW_OPENED -> "OPENED"
                            WindowEvent.WINDOW_CLOSED -> "CLOSED"
                            WindowEvent.WINDOW_ACTIVATED -> "ACTIVATED"
                            WindowEvent.WINDOW_DEACTIVATED -> "DEACTIVATED"
                            WindowEvent.WINDOW_GAINED_FOCUS -> "GAINED_FOCUS"
                            WindowEvent.WINDOW_LOST_FOCUS -> "LOST_FOCUS"
                            else -> "WIN_${event.id}"
                        }
                    log("AWT_WIN $idName win=${describeComp(event.window)} opp=${describeComp(event.oppositeWindow)}")
                }
                is InputMethodEvent -> {
                    log(
                        "AWT_IME id=${event.id} cons=${event.isConsumed} committed=${event.committedCharacterCount} " +
                            "src=${describeComp(event.source as? Component)}"
                    )
                }
                is MouseEvent -> {
                    if (event.id == MouseEvent.MOUSE_PRESSED || event.id == MouseEvent.MOUSE_RELEASED) {
                        val idName = if (event.id == MouseEvent.MOUSE_PRESSED) "M_PRESS" else "M_REL"
                        log("AWT_$idName (${event.x},${event.y}) src=${describeComp(event.component)}")
                    }
                }
            }
        }
        awtListener = listener
        Toolkit.getDefaultToolkit()
            .addAWTEventListener(
                listener,
                AWTEvent.KEY_EVENT_MASK or
                    AWTEvent.FOCUS_EVENT_MASK or
                    AWTEvent.WINDOW_EVENT_MASK or
                    AWTEvent.WINDOW_FOCUS_EVENT_MASK or
                    AWTEvent.INPUT_METHOD_EVENT_MASK or
                    AWTEvent.MOUSE_EVENT_MASK,
            )
    }

    fun uninstallAwtListener() {
        awtListener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        awtListener = null
    }

    fun snapshot(stage: String, window: ComposeWindow, automator: ComposeAutomator?, includeTree: Boolean = false) {
        val sb = StringBuilder("SNAP[$stage] ")
        SwingUtilities.invokeAndWait {
            val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            sb.append("winFoc=").append(window.isFocused)
            sb.append(" winAct=").append(window.isActive)
            sb.append(" winShow=").append(window.isShowing)
            sb.append(" winFO=").append(describeComp(window.focusOwner))
            sb.append(" winMostRecentFO=").append(describeComp(window.mostRecentFocusOwner))
            sb.append(" kfmFO=").append(describeComp(kfm.focusOwner))
            sb.append(" kfmPermFO=").append(describeComp(kfm.permanentFocusOwner))
            sb.append(" kfmFocWin=").append(describeComp(kfm.focusedWindow))
            sb.append(" kfmActWin=").append(describeComp(kfm.activeWindow))
            sb.append(" renderApi=").append(runCatching { window.renderApi }.getOrElse { it.javaClass.simpleName })
            sb.append(" dpi=").append(runCatching { window.graphicsConfiguration?.defaultTransform?.scaleX }.getOrNull())
            sb.append(" allWins=").append(Window.getWindows().count { it.isShowing })
            sb.append("/").append(Window.getWindows().size)
            sb.append(" scene=[").append(inspectComposeScene(window)).append("]")
            if (includeTree) {
                sb.append("\n  ENV os=").append(System.getProperty("os.name"))
                    .append(" ").append(System.getProperty("os.version"))
                    .append(" java=").append(System.getProperty("java.version"))
                    .append(" ").append(System.getProperty("java.vendor"))
                    .append(" loc=").append(System.getProperty("user.language")).append("_")
                    .append(System.getProperty("user.country"))
                sb.append("\n  TREE: ")
                dumpTree(window, sb, "")
                sb.append("\n  WINDOWS: ")
                for (w in Window.getWindows()) {
                    sb.append(describeComp(w))
                        .append("(show=").append(w.isShowing)
                        .append(",vis=").append(w.isVisible)
                        .append(",foc=").append(w.isFocused)
                        .append(",fo=").append(describeComp(w.focusOwner))
                        .append(") ")
                }
            }
        }
        val ss = speedSearchState.get()
        if (ss != null) {
            sb.append(" ss(vis=").append(ss.isVisible)
            sb.append(",searchText='").append(ss.searchText)
            sb.append("',tf='").append(ss.searchState.textFieldState.text)
            sb.append("',hasMatches=").append(ss.hasMatches)
            sb.append(",matches=").append(ss.matchingIndexes).append(")")
        }
        if (automator != null) {
            runCatching {
                val popupNodes = automator.findByTestTag(POPUP_TAG)
                val searchNodes = automator.findByTestTag(SEARCH_INPUT_TAG)
                val openNodes = automator.findByTestTag(OPEN_TAG)
                sb.append(" sem(popup=").append(popupNodes.size)
                sb.append(",search=").append(searchNodes.size)
                sb.append(",searchText='").append(searchNodes.firstOrNull()?.editableText)
                sb.append("',searchFoc=").append(searchNodes.firstOrNull()?.isFocused)
                sb.append(",openFoc=").append(openNodes.firstOrNull()?.isFocused).append(")")
            }.onFailure { sb.append(" semErr=").append(it.javaClass.simpleName) }
        }
        log(sb.toString())
    }

    fun dump(header: String) {
        val sb = StringBuilder()
        sb.appendLine("=== $header [$label] (comboEscPrev=${comboSawEscapePreview.get()} comboEscKey=${comboSawEscapeKey.get()} hostEscPrev=${hostSawEscapePreview.get()} hostEsc=${hostSawEscape.get()}) ===")
        for (line in events) {
            sb.appendLine("  $line")
        }
        sb.appendLine("=== END $header [$label] ===")
        print(sb.toString())
        System.out.flush()
    }
}

private fun describeComp(c: Component?): String {
    if (c == null) return "null"
    val simple = c.javaClass.name.substringAfterLast('.')
    val kl = runCatching { c.keyListeners.size }.getOrDefault(-1)
    val iml = runCatching { c.inputMethodListeners.size }.getOrDefault(-1)
    return "$simple@${Integer.toHexString(System.identityHashCode(c))}(foc=${c.isFocusable},fo=${c.isFocusOwner},kl=$kl,iml=$iml)"
}

private fun dumpTree(c: Component, sb: StringBuilder, indent: String) {
    sb.append(indent).append(describeComp(c)).append("; ")
    if (c is Container) {
        for (child in c.components) {
            dumpTree(child, sb, "$indent  ")
        }
    }
}

private fun readField(obj: Any?, name: String): Any? {
    if (obj == null) return null
    var cls: Class<*>? = obj.javaClass
    while (cls != null) {
        try {
            val f = cls.getDeclaredField(name)
            f.isAccessible = true
            return f.get(obj)
        } catch (_: NoSuchFieldException) {
            cls = cls.superclass
        }
    }
    return null
}

private fun inspectComposeScene(window: ComposeWindow): String =
    runCatching {
        val panel = readField(window, "composePanel")
        val container = readField(panel, "_composeContainer")
        val mediator = readField(container, "mediator")
        val inv = readField(mediator, "invisibleComponent") as? Component
        val imr = readField(mediator, "currentInputMethodRequests")
        val sceneDelegate = readField(mediator, "scene\$delegate") as? Lazy<*>
        val scene = if (sceneDelegate?.isInitialized() == true) sceneDelegate.value else null
        val focusedLayer = readField(scene, "focusedLayer")
        val layers = readField(scene, "layers") as? Collection<*>
        val mainOwner = readField(scene, "mainOwner")
        val focusOwner = mainOwner?.javaClass?.methods?.firstOrNull { it.name == "getFocusOwner" && it.parameterCount == 0 }?.invoke(mainOwner)
        val activeTarget = readField(focusOwner, "activeFocusTargetNode")
        val invMgr = readField(focusOwner, "focusInvalidationManager")
        val hasPendingInv = invMgr?.javaClass?.methods?.firstOrNull { it.name == "hasPendingInvalidation" && it.parameterCount == 0 }?.invoke(invMgr)
        "invFoc=${inv?.isFocusable},invFO=${inv?.isFocusOwner},imr=${imr != null}," +
            "scene=${scene?.javaClass?.simpleName},layers=${layers?.size},focusedLayer=${focusedLayer != null}," +
            "activeTarget=${activeTarget != null},pendingInv=$hasPendingInv"
    }.getOrElse { "err=${it.javaClass.simpleName}:${it.message}" }

private fun inspectRenderAndRecomposer(window: ComposeWindow): String =
    runCatching {
        val panel = readField(window, "composePanel")
        val container = readField(panel, "_composeContainer")
        val mediator = readField(container, "mediator")
        val frameRecomposer = readField(mediator, "frameRecomposer")
        val recomposer = readField(frameRecomposer, "recomposer")
        val hasPendingWork = recomposer?.javaClass?.methods?.firstOrNull { it.name == "getHasPendingWork" && it.parameterCount == 0 }?.invoke(recomposer)
        val frameClock = readField(frameRecomposer, "frameClock")
        val hasAwaiters = frameClock?.javaClass?.methods?.firstOrNull { it.name == "getHasAwaiters" && it.parameterCount == 0 }?.invoke(frameClock)
        val contentComp = mediator?.javaClass?.methods?.firstOrNull { it.name == "getContentComponent" && it.parameterCount == 0 }?.invoke(mediator)
        val frameDriverMgr = readField(contentComp, "frameDriverManager")
        val frameDriver = readField(frameDriverMgr, "current")
        val producer = readField(frameDriver, "producer")
        val renderer = readField(producer, "renderer")
        val scheduler = readField(frameDriver, "scheduler")
        val frameDisp = readField(scheduler, "frameDispatcher")
        val frameScheduled = readField(frameDisp, "frameScheduled")
        val renderInfo = runCatching { frameDriver?.javaClass?.methods?.firstOrNull { it.name == "getRenderInfo" && it.parameterCount == 0 }?.invoke(frameDriver) }.getOrNull()?.toString()?.replace("\n", " | ")
        val renderApi = runCatching { contentComp?.javaClass?.methods?.firstOrNull { it.name == "getRenderApi" && it.parameterCount == 0 }?.invoke(contentComp) }.getOrNull()
        val isShowing = (contentComp as? Component)?.isShowing
        "recompPending=$hasPendingWork clockAwaiters=$hasAwaiters renderApi=$renderApi env=${System.getenv("SKIKO_RENDER_API")} prop=${System.getProperty("skiko.renderApi")} " +
            "renderer=${renderer?.javaClass?.simpleName} frameScheduled=$frameScheduled skiaShowing=$isShowing renderInfo=[$renderInfo]"
    }.getOrElse { "err=${it.javaClass.simpleName}:${it.message}" }

private fun dumpThreads(): String {
    val sb = StringBuilder()
    for ((t, stack) in Thread.getAllStackTraces().entries.sortedBy { it.key.name }) {
        val name = t.name
        val interesting =
            name.contains("skiko", ignoreCase = true) ||
                name.contains("AWT", ignoreCase = true) ||
                name.contains("spectre", ignoreCase = true) ||
                name.contains("DefaultDispatcher", ignoreCase = true) ||
                name.contains("main", ignoreCase = true) ||
                stack.any { it.className.contains("skiko") || it.className.contains("compose") }
        if (!interesting) continue
        sb.append("  THREAD [").append(name).append("] state=").append(t.state).append('\n')
        for (frame in stack.take(25)) {
            sb.append("    at ").append(frame).append('\n')
        }
    }
    return sb.toString()
}

/**
 * Reports whether the submenu sits next to the item that opened it, rather than next to the window origin.
 *
 * A submenu that reads the owner window's component instead of its parent dialog lands near the window origin. The two
 * distances invert in that case, so a comparison tells them apart without a pixel tolerance.
 */
private fun anchoredToItem(submenu: Rectangle, item: Rectangle, origin: Point): Boolean {
    val toItem = hypot((submenu.x - item.x).toDouble(), (submenu.y - item.y).toDouble())
    val toOrigin = hypot((submenu.x - origin.x).toDouble(), (submenu.y - origin.y).toDouble())
    return toItem < toOrigin
}

private class Case(
    val name: String,
    val open: suspend (ComposeAutomator, RobotDriver) -> Unit,
    val measure: suspend (ComposeAutomator) -> String,
    /** The keys to press, in order, once the case is open. */
    val keys: List<Int> = listOf(KeyEvent.VK_ESCAPE),
    /**
     * The absolute measurement both renderers must produce, for scenarios with no component logic masking the
     * renderer's own decision. Differential parity alone cannot catch both renderers being wrong the same way.
     */
    val expect: String? = null,
    val content: @Composable () -> Unit,
)

private fun ComposeAutomator.hasTag(tag: String) = findByTestTag(tag).isNotEmpty()

private fun ComposeAutomator.hasText(text: String) = findByText(text).isNotEmpty()

private fun host() = "hostSawEscape=" + hostSawEscape.get()

private val openByClick: suspend (ComposeAutomator, RobotDriver) -> Unit = { a, _ ->
    a.click(a.waitForNode(tag = OPEN_TAG))
    a.waitForNode(tag = POPUP_TAG)
}

/** Clicks the chevron rather than the centre, which on an EditableComboBox only focuses the text field. */
private val openByChevron: suspend (ComposeAutomator, RobotDriver) -> Unit = { a, driver ->
    val bounds = a.waitForNode(tag = OPEN_TAG).boundsOnScreen
    driver.click(bounds.x + bounds.width - 8, bounds.y + bounds.height / 2)
    a.waitForNode(tag = POPUP_TAG)
}

private val CONTROL_CASE =
    // Control: no popup at all. If this does not report hostSawEscape=true, the probe itself is broken
    // and every other hostSawEscape reading in this run is meaningless.
    Case(
        name = "control(noPopup)",
        open = { a, _ -> a.click(a.waitForNode(tag = OPEN_TAG)) },
        measure = { "hostSawEscape=" + hostSawEscape.get() },
    ) {
        DefaultButton(onClick = {}, modifier = Modifier.testTag(OPEN_TAG)) { Text("Plain button") }
    }

private val CASES =
    listOf(
        Case(name = "comboBox", open = openByClick, measure = { "popupOpen=" + it.hasTag(POPUP_TAG) + " " + host() }) {
            ComboBox(labelText = "Combo", modifier = Modifier.testTag(OPEN_TAG).width(240.dp)) { Text("Content") }
        },
        Case(
            name = "editableComboBox",
            open = openByChevron,
            measure = { "popupOpen=" + it.hasTag(POPUP_TAG) + " " + host() },
        ) {
            EditableComboBox(
                textFieldState = remember { TextFieldState("Editable") },
                modifier = Modifier.testTag(OPEN_TAG).width(240.dp),
            ) {
                Text("Content")
            }
        },
        Case(
            name = "popupMenu(focusable=true)",
            open = { a, _ ->
                a.click(a.waitForNode(tag = OPEN_TAG))
                a.waitForNode(tag = MENU_TAG)
            },
            measure = { "menuOpen=" + it.hasTag(MENU_TAG) + " " + host() },
        ) {
            MenuWithSubmenu()
        },
        Case(
            name = "submenu(nested)",
            open = { a, _ ->
                a.click(a.waitForNode(tag = OPEN_TAG))
                a.waitForNode(tag = MENU_TAG)
                a.click(a.waitForNode(text = "Open submenu"))
                a.waitForNode(text = "Sub item")
            },
            measure = {
                "parentMenuOpen=" + it.hasTag(MENU_TAG) + " submenuOpen=" + it.hasText("Sub item") + " " + host()
            },
        ) {
            MenuWithSubmenu()
        },
        Case(
            name = "gotIt(withButtons)",
            open = { a, _ ->
                a.click(a.waitForNode(tag = OPEN_TAG))
                a.waitForNode(text = "Tooltip body")
            },
            measure = { "tooltipOpen=" + it.hasText("Tooltip body") + " " + host() },
        ) {
            GotIt(buttons = GotItButtons.default())
        },
        Case(
            name = "gotIt(noButtons)",
            open = { a, _ ->
                a.click(a.waitForNode(tag = OPEN_TAG))
                a.waitForNode(text = "Tooltip body")
            },
            measure = { "tooltipOpen=" + it.hasText("Tooltip body") + " " + host() },
        ) {
            GotIt(buttons = GotItButtons.None)
        },
        Case(
            name = "rawPopup(dismissOnBackPress=true)",
            open = { a, _ ->
                a.click(a.waitForNode(tag = OPEN_TAG))
                a.waitForNode(tag = RAW_POPUP_TAG)
            },
            measure = { "popupOpen=" + it.hasTag(RAW_POPUP_TAG) + " " + host() },
            // Escape dismisses, and the focusable popup consumes the key whether it dismissed or not.
            expect = "popupOpen=false hostSawEscape=false",
        ) {
            RawPopup(dismissOnBackPress = true)
        },
        Case(
            name = "rawPopup(nonFocusable)",
            open = { a, _ ->
                a.click(a.waitForNode(tag = OPEN_TAG))
                a.waitForNode(tag = RAW_POPUP_TAG)
            },
            measure = { "popupOpen=" + it.hasTag(RAW_POPUP_TAG) + " " + host() },
            // A non-focusable popup cannot act on Escape at all: it must neither dismiss nor consume, so the
            // key reaches the host. Proves unconsumed Escape is forwarded while a native popup window exists.
            expect = "popupOpen=true hostSawEscape=true",
        ) {
            RawPopup(focusable = false)
        },
        Case(
            name = "rawPopup(dismissOnBackPress=false)",
            open = { a, _ ->
                a.click(a.waitForNode(tag = OPEN_TAG))
                a.waitForNode(tag = RAW_POPUP_TAG)
            },
            measure = { "popupOpen=" + it.hasTag(RAW_POPUP_TAG) + " " + host() },
            // No dismissal, but the focusable popup still consumes the key.
            expect = "popupOpen=true hostSawEscape=false",
        ) {
            RawPopup(dismissOnBackPress = false)
        },
        // Arrow keys never reached this matrix before, which is how a renderer that moved the owner window's focus
        // stayed hidden. The popup must act on its own focus, so the owner's tree must not gain focus at all.
        Case(
            name = "arrowDown(menu)",
            open = { a, _ ->
                a.click(a.waitForNode(tag = OPEN_TAG))
                a.waitForNode(tag = MENU_TAG)
            },
            keys = listOf(KeyEvent.VK_DOWN),
            measure = { "menuOpen=" + it.hasTag(MENU_TAG) + " hostGainedFocus=" + hostGainedFocus.get() },
            expect = "menuOpen=true hostGainedFocus=false",
        ) {
            MenuWithSubmenu()
        },
        // Right opens a submenu through a focus-based handler on the item, not through the popup's key callback. It
        // only works when the key reaches the popup's own Compose scene.
        Case(
            name = "arrowRight(opensSubmenu)",
            open = { a, _ ->
                a.click(a.waitForNode(tag = OPEN_TAG))
                a.waitForNode(tag = MENU_TAG)
            },
            keys = listOf(KeyEvent.VK_DOWN, KeyEvent.VK_RIGHT),
            measure = { "submenuOpen=" + it.hasText("Sub item") + " hostGainedFocus=" + hostGainedFocus.get() },
            expect = "submenuOpen=true hostGainedFocus=false",
        ) {
            MenuWithSubmenu()
        },
        // With a submenu open, the parent menu must leave the key to it. Both menus listen for every key in the
        // application, and the parent listens first.
        Case(
            name = "arrowDown(submenuOpen)",
            open = { a, _ ->
                a.click(a.waitForNode(tag = OPEN_TAG))
                a.waitForNode(tag = MENU_TAG)
            },
            keys = listOf(KeyEvent.VK_DOWN, KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN),
            measure = {
                "parentMenuOpen=" +
                    it.hasTag(MENU_TAG) +
                    " submenuOpen=" +
                    it.hasText("Sub item") +
                    " hostGainedFocus=" +
                    hostGainedFocus.get()
            },
            expect = "parentMenuOpen=true submenuOpen=true hostGainedFocus=false",
        ) {
            MenuWithSubmenu()
        },
        // A parent menu that takes its submenu's key moves its own focus, so the values above do not change.
        // A second submenu opens only when the key reaches the first one, which makes the theft visible.
        Case(
            name = "arrowRight(nestedSubmenu)",
            open = { a, _ ->
                a.click(a.waitForNode(tag = OPEN_TAG))
                a.waitForNode(tag = MENU_TAG)
            },
            keys = listOf(KeyEvent.VK_DOWN, KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN, KeyEvent.VK_RIGHT),
            measure = { "nestedOpen=" + it.hasText("Nested item") + " hostGainedFocus=" + hostGainedFocus.get() },
            expect = "nestedOpen=true hostGainedFocus=false",
        ) {
            MenuWithNestedSubmenu()
        },
        // The submenu must be positioned against the item that opened it. When it reads the owner window instead,
        // it lands near the window origin, which inverts these two distances.
        Case(
            name = "submenuAnchoring",
            open = { a, _ ->
                a.click(a.waitForNode(tag = OPEN_TAG))
                a.waitForNode(tag = MENU_TAG)
            },
            keys = listOf(KeyEvent.VK_DOWN, KeyEvent.VK_RIGHT),
            measure = { a ->
                val submenu = a.findByText("Sub item").firstOrNull()?.boundsOnScreen
                val item = a.findByText("Open submenu").firstOrNull()?.boundsOnScreen
                val origin = hostWindow.get()?.takeIf { it.isShowing }?.locationOnScreen
                val anchored =
                    if (submenu == null || item == null || origin == null) {
                        "unknown"
                    } else {
                        anchoredToItem(submenu, item, origin).toString()
                    }
                "submenuOpen=" + (submenu != null) + " anchoredToItem=" + anchored
            },
            expect = "submenuOpen=true anchoredToItem=true",
        ) {
            MenuWithSubmenuAwayFromOrigin()
        },
        Case(
            name = "submenuAlignment",
            open = { a, _ ->
                a.click(a.waitForNode(tag = OPEN_TAG))
                a.waitForNode(tag = MENU_TAG)
            },
            keys = listOf(KeyEvent.VK_DOWN, KeyEvent.VK_RIGHT),
            measure = { a ->
                val parentMenu = a.findByTestTag(MENU_TAG).firstOrNull()?.boundsOnScreen
                val parentItem = a.findByText("Open submenu").firstOrNull()?.boundsOnScreen
                val subItem = a.findByText("Sub item").firstOrNull()?.boundsOnScreen
                if (parentMenu == null || parentItem == null || subItem == null) {
                    "missing"
                } else {
                    val verticalDelta = subItem.y - parentItem.y
                    val submenuLeft = subItem.x - (parentItem.x - parentMenu.x)
                    val horizontalDelta = submenuLeft - (parentMenu.x + parentMenu.width)
                    "verticalDelta=$verticalDelta horizontalDelta=$horizontalDelta"
                }
            },
            expect = "verticalDelta=0 horizontalDelta=0",
        ) {
            MenuWithSubmenu()
        },
        Case(
            name = "speedSearchCombo(escape#1)",
            open = { a, _ ->
                val diag = activeDiag.get()
                diag?.mark("before_click")
                a.click(a.waitForNode(tag = OPEN_TAG))
                diag?.mark("after_click")
                a.waitForNode(tag = POPUP_TAG)
                diag?.mark("after_popup")
                a.typeText("Alpha")
                diag?.mark("after_type")
                a.waitForNode(tag = SEARCH_INPUT_TAG)
                diag?.mark("after_search")
            },
            measure = {
                "popupOpen=" + it.hasTag(POPUP_TAG) + " searchOpen=" + it.hasTag(SEARCH_INPUT_TAG) + " " + host()
            },
        ) {
            SpeedSearchCombo()
        },
    )

class PopupRendererParitySpectreTest {
    @Test
    fun `the custom renderer matches Compose's own popup for every component`(): Unit = runSpectreTest {
        // The control doubles as the probe's canary: if Escape does not reach the host window with no popup
        // involved, every other hostSawEscape reading is meaningless, and the parity comparison below would
        // pass vacuously on a dead probe. Gate on it explicitly.
        for (custom in listOf(false, true)) {
            assertEquals(
                expected = "hostSawEscape=true",
                actual = measure(custom = custom, case = CONTROL_CASE),
                message =
                    "Escape probe is broken on ${label(custom)}: the host window did not see Escape with no popup showing",
            )
        }

        val order = System.getenv("JEWEL_DIAG_ORDER") ?: "master"
        val casesToRun = if (order == "pre1485") CASES.filterNot { it.name == "submenuAlignment" } else CASES

        val divergences = mutableListOf<String>()
        for (case in casesToRun) {
            val withCompose = measure(custom = false, case = case)
            val withJDialog = measure(custom = true, case = case)
            if (withCompose != withJDialog) {
                divergences += "${case.name}: defaultCompose[$withCompose] != JDialogRenderer[$withJDialog]"
            }
            val expected = case.expect
            if (expected != null) {
                if (withCompose != expected) {
                    divergences += "${case.name}: defaultCompose[$withCompose] != expected[$expected]"
                }
                if (withJDialog != expected) {
                    divergences += "${case.name}: JDialogRenderer[$withJDialog] != expected[$expected]"
                }
            }
        }
        assertEquals(
            emptyList(),
            divergences,
            "JDialogRenderer must be indistinguishable from Compose's own popup for these components",
        )
    }

    private suspend fun measure(custom: Boolean, case: Case): String {
        val previousRenderer = JewelFlags.useCustomPopupRenderer
        JewelFlags.useCustomPopupRenderer = custom
        val isSpeedSearch = case.name == "speedSearchCombo(escape#1)"
        val diag = DiagCollector("${label(custom)}:${case.name}")
        if (isSpeedSearch) {
            activeDiag.set(diag)
            diag.installAwtListener()
        }
        val app = MatrixApp(case.content)
        try {
            diag.mark("start")
            app.start()
            val window = app.awaitWindow()
            diag.mark("awaitWindow")
            hostWindow.set(window)
            val driver = RobotDriver.synthetic(window)
            val automator = ComposeAutomator.inProcess(driver)
            case.open(automator, driver)
            automator.waitForIdle()
            diag.mark("waitForIdle")
            hostSawEscape.set(false)
            hostGainedFocus.set(false)

            for (key in case.keys) {
                automator.pressKey(key)
                diag.mark("pressKey_$key")
                delay(250.milliseconds)
            }
            delay(1500.milliseconds)
            diag.mark("afterDelays")
            automator.refreshWindows()

            val result = case.measure(automator)
            diag.mark("measured")
            println("PARITY renderer=" + label(custom) + " case=" + case.name + " " + result)
            if (case.name == "control(noPopup)" && !custom) {
                println("DIAG_RENDER_INFO ${inspectRenderAndRecomposer(window)}")
            }
            val unexpectedSpeedSearch = isSpeedSearch && result != "popupOpen=true searchOpen=false hostSawEscape=false"
            val unexpectedCombo = case.name == "comboBox" && result != "popupOpen=false hostSawEscape=false"
            val unexpectedOther = case.expect != null && result != case.expect
            if (isSpeedSearch) {
                println("DIAG_TIMING [${diag.label}] ${diag.timingSummary()}")
            }
            if (unexpectedSpeedSearch || unexpectedCombo || unexpectedOther) {
                diag.snapshot("POST_FAILURE", window, automator, includeTree = true)
                diag.log("RENDER_AND_RECOMPOSER: ${inspectRenderAndRecomposer(window)}")
                diag.log("THREAD_DUMP_AT_FAILURE:\n${dumpThreads()}")
                // Wait up to 10s WITHOUT sending another key to see if Compose spontaneously recomposes when the stall clears
                val waitStart = System.nanoTime()
                var clearedAtMs = -1L
                while ((System.nanoTime() - waitStart) / 1_000_000 < 10_000L) {
                    delay(250.milliseconds)
                    automator.refreshWindows()
                    val nowResult = case.measure(automator)
                    if (nowResult != result) {
                        clearedAtMs = (System.nanoTime() - waitStart) / 1_000_000
                        diag.log("SPONTANEOUS_CHANGE after +${clearedAtMs}ms -> [$nowResult]")
                        break
                    }
                }
                if (clearedAtMs < 0) {
                    diag.log("NO_SPONTANEOUS_CHANGE after +10000ms; RENDER_AND_RECOMPOSER: ${inspectRenderAndRecomposer(window)}")
                    diag.log("THREAD_DUMP_AFTER_10S:\n${dumpThreads()}")
                } else {
                    diag.snapshot("AFTER_SPONTANEOUS_CHANGE", window, automator)
                }
                diag.dump("DIAG FAILURE TRACE")
            }
            return result
        } finally {
            diag.uninstallAwtListener()
            if (isSpeedSearch) activeDiag.compareAndSet(diag, null)
            val stopStartNanos = System.nanoTime()
            val stopDone = AtomicBoolean(false)
            val watchdog =
                thread(name = "diag-stop-watchdog", isDaemon = true) {
                    try {
                        Thread.sleep(200L)
                        if (!stopDone.get()) {
                            println("=== DIAG SLOW STOP (>200ms) [${diag.label}] ===\n${dumpThreads()}=== END DIAG SLOW STOP ===")
                            System.out.flush()
                        }
                    } catch (_: InterruptedException) {
                        // normal stop
                    }
                }
            app.stop()
            stopDone.set(true)
            watchdog.interrupt()
            val stopMs = (System.nanoTime() - stopStartNanos) / 1_000_000
            if (isSpeedSearch || stopMs >= 200L) {
                println("DIAG_STOP [${diag.label}] stopMs=$stopMs totalWithStopMs=${diag.elapsedMs()}")
            }
            JewelFlags.useCustomPopupRenderer = previousRenderer
        }
    }

    private fun label(custom: Boolean) = if (custom) "JDialogRenderer" else "defaultCompose"
}

@Composable
private fun MenuWithSubmenu() {
    var visible by remember { mutableStateOf(false) }
    Box {
        DefaultButton(onClick = { visible = true }, modifier = Modifier.testTag(OPEN_TAG)) { Text("Menu") }
        if (visible) {
            PopupMenu(
                onDismissRequest = {
                    visible = false
                    true
                },
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.testTag(MENU_TAG),
            ) {
                submenu(submenu = { selectableItem(false, onClick = {}) { Text("Sub item") } }) { Text("Open submenu") }
            }
        }
    }
}

/** The submenu's own item opens a second submenu, so a key that reaches the first submenu has a visible effect. */
@Composable
private fun MenuWithNestedSubmenu() {
    var visible by remember { mutableStateOf(false) }
    Box {
        DefaultButton(onClick = { visible = true }, modifier = Modifier.testTag(OPEN_TAG)) { Text("Menu") }
        if (visible) {
            PopupMenu(
                onDismissRequest = {
                    visible = false
                    true
                },
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.testTag(MENU_TAG),
            ) {
                submenu(
                    submenu = {
                        submenu(submenu = { selectableItem(false, onClick = {}) { Text("Nested item") } }) {
                            Text("Sub item")
                        }
                    }
                ) {
                    Text("Open submenu")
                }
            }
        }
    }
}

/** Places the menu far from the window origin, so the anchoring comparison has two clearly different distances. */
@Composable
private fun MenuWithSubmenuAwayFromOrigin() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) { MenuWithSubmenu() }
}

@Composable
private fun GotIt(buttons: GotItButtons) {
    var visible by remember { mutableStateOf(false) }
    GotItTooltip(text = "Tooltip body", visible = visible, onDismiss = { visible = false }, buttons = buttons) {
        DefaultButton(onClick = { visible = true }, modifier = Modifier.testTag(OPEN_TAG)) { Text("Show") }
    }
}

/**
 * A popup with no component logic behind it: nothing else handles Escape, so what happens is entirely the renderer's
 * decision. Every other case in this matrix has a component that closes itself on Escape, which masks whatever the
 * renderer does.
 */
@Composable
private fun RawPopup(focusable: Boolean = true, dismissOnBackPress: Boolean = true) {
    var visible by remember { mutableStateOf(false) }
    Box {
        DefaultButton(onClick = { visible = true }, modifier = Modifier.testTag(OPEN_TAG)) { Text("Open raw popup") }
        if (visible) {
            PopupContainer(
                onDismissRequest = { visible = false },
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.testTag(RAW_POPUP_TAG),
                popupProperties = PopupProperties(focusable = focusable, dismissOnBackPress = dismissOnBackPress),
            ) {
                Text("Raw popup content")
            }
        }
    }
}

@Composable
private fun SpeedSearchCombo() {
    val state = rememberSpeedSearchState()
    activeDiag.get()?.speedSearchState?.set(state)
    SpeedSearchArea(state = state, dismissOnLoseFocus = false) {
        SpeedSearchableComboBox(
            items = listOf("Alpha", "Beta", "Gamma"),
            selectedIndex = 0,
            onSelectedItemChange = {},
            modifier = Modifier.testTag(OPEN_TAG).width(240.dp),
        )
    }
}

private class MatrixApp(private val content: @Composable () -> Unit) {
    private val exitApplication = AtomicReference<(() -> Unit)?>(null)
    private val window = AtomicReference<ComposeWindow?>(null)
    private val appThread = AtomicReference<Thread?>(null)

    fun start() {
        val startedThread =
            thread(name = "spectre-matrix-window", isDaemon = true) {
                application(exitProcessOnExit = false) {
                    exitApplication.set(::exitApplication)
                    JewelWindow(onCloseRequest = ::exitApplication, title = "Jewel popup matrix") {
                        this@MatrixApp.window.compareAndSet(null, window)
                        IntUiTheme {
                            Column(
                                modifier =
                                    Modifier.padding(24.dp)
                                        .onFocusEvent { if (it.hasFocus) hostGainedFocus.set(true) }
                                        .onKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                                hostSawEscape.set(true)
                                            }
                                            false
                                        }
                            ) {
                                content()
                            }
                        }
                    }
                }
            }
        appThread.set(startedThread)
    }

    fun stop() {
        stopApplicationWindow(exitApplication, window, appThread)
    }

    suspend fun awaitWindow(): ComposeWindow = window.awaitWindow()
}
