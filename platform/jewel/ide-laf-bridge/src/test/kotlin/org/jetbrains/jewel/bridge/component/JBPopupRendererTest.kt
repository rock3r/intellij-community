// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.bridge.component

import androidx.compose.runtime.Composer
import java.lang.reflect.Method
import org.jetbrains.jewel.ui.component.PopupRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [JBPopupRenderer], the IJP-side counterpart of the standalone `JDialogRenderer`.
 *
 * These assert the shape of its [PopupRenderer] implementation rather than its behaviour: driving it end to end needs a
 * running IDE and a display, because it builds a real `JBPopup` through `JBPopupFactory` and shows it on screen. That
 * headful coverage is the rest of this issue's work.
 *
 * ## What the headful lane must cover
 *
 * Nothing in CI currently exercises [JBPopupRenderer]'s behaviour at all. Reverting the whole file to any earlier
 * revision leaves every test that compiles this module green, so a bridge regression ships silently. Everything we know
 * about how it behaves comes from manual measurement against a dev IDE. Specifically, the lane needs to pin:
 * 1. Escape on a hovered `ComboBox` closes its popup. This was broken on the bridge and is only measurable here:
 *    `JDialogRenderer` hides the same fault behind its window-ownership check, so the standalone suite passes either
 *    way.
 * 2. Escape on a shown `GotItTooltip` closes it. Same shape as above: the tooltip handles Escape on its anchor, and a
 *    renderer that claims the key starves that handler, leaving the tooltip stuck open.
 * 3. Clicking the chevron of an open `ComboBox` closes it without reopening, which depends on the renderer pushing
 *    property changes onto the live `AbstractPopup` rather than the values captured when it was built.
 * 4. The non-focusable divergence below.
 *
 * ## Known divergence from Compose, deliberately unresolved
 *
 * `StackingPopupDispatcherImpl.dispatchKeyEvent` routes Escape to the top-of-stack popup regardless of where focus is.
 * So on the bridge, a **non-focusable** popup that has a dismissal callback and allows `dismissOnBackPress` will
 * dismiss and consume Escape. Neither Compose's own popup nor `JDialogRenderer` does that: Compose ignores Escape
 * entirely for non-focusable popups, and `JDialogRenderer`'s ownership check makes them unreachable.
 *
 * `TooltipArea` is the concrete case — it is non-focusable with a non-null callback and the default
 * `dismissOnBackPress`, so the first Escape hides a hover tooltip on the bridge and is swallowed, while standalone
 * leaves it up and lets the key through. This is long-standing rather than new, but the standalone renderer-parity test
 * cannot see it, and it will stay invisible until this lane exists.
 */
internal class JBPopupRendererTest {
    @Test
    fun `overrides every PopupRenderer Popup overload`() {
        val inherited = popupOverloads.filterNot { it.isOverriddenByBridge() }

        assertTrue(
            "JBPopupRenderer must declare its own override of every PopupRenderer.Popup overload, or the interface " +
                "default and the bridge's deprecated overload will delegate to each other forever. Inherited: " +
                inherited.joinToString { it.toGenericString() },
            inherited.isEmpty(),
        )
    }

    @Test
    fun `implements the windowShape-aware overload`() {
        assertEquals("PopupRenderer is expected to declare exactly two Popup overloads", 2, popupOverloads.size)

        val (deprecated, windowShapeAware) = popupOverloads.sortedBy { it.parameterCount }

        assertEquals(
            "The windowShape-aware overload is expected to take exactly one parameter more than the deprecated one",
            deprecated.parameterCount + 1,
            windowShapeAware.parameterCount,
        )
        assertTrue(
            "The extra parameter is expected to be the IntSize -> Shape factory, but the overload is: " +
                windowShapeAware.toGenericString(),
            windowShapeAware.toGenericString().contains("IntSize") &&
                windowShapeAware.toGenericString().contains("java.awt.Shape"),
        )
        assertTrue(
            "JBPopupRenderer must implement the windowShape-aware overload, even though it ignores the shape: " +
                "JBPopup does not expose native window shaping",
            windowShapeAware.isOverriddenByBridge(),
        )
    }

    @Test
    fun `keeps both overloads composable`() {
        val notComposable =
            JBPopupRenderer::class
                .java
                .declaredMethods
                .filter { it.name == "Popup" && !it.isSynthetic }
                .filterNot { method -> method.parameterTypes.any { it == Composer::class.java } }

        assertTrue(
            "Every JBPopupRenderer.Popup override must stay @Composable, or it cannot render a popup at all. " +
                "Not composable: " +
                notComposable.joinToString { it.toGenericString() },
            notComposable.isEmpty(),
        )
    }

    /**
     * The overloads declared by [PopupRenderer] itself, minus the synthetic bridge the Compose compiler emits to carry
     * the `windowShape` default value.
     */
    private val popupOverloads: List<Method>
        get() = PopupRenderer::class.java.declaredMethods.filter { it.name == "Popup" && !it.isSynthetic }

    private fun Method.isOverriddenByBridge(): Boolean =
        runCatching { JBPopupRenderer::class.java.getDeclaredMethod(name, *parameterTypes) }.isSuccess
}
