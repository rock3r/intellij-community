// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.intui.standalone.popup

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.util.fastRoundToInt
import java.awt.Component
import java.awt.Point
import javax.swing.SwingUtilities
import kotlin.math.floor

/**
 * Returns the screen density of the component's current monitor.
 *
 * Derived from the default transform scale of the component's graphics configuration.
 */
fun Component.density(): Float = graphicsConfiguration.device.defaultConfiguration.defaultTransform.scaleX.toFloat()

/**
 * Calculates a component's location relative to the top-left corner of the screen it is currently on. This is useful in
 * multi-monitor setups where [Component.getLocationOnScreen] can return negative values for screens positioned to the
 * left of or above the primary display.
 *
 * @return A [Point] containing the x and y coordinates relative to the component's current monitor.
 */
fun Component.locationOnDisplay(): Point {
    val globalLocation = locationOnScreen
    val screenBounds = graphicsConfiguration.bounds
    return Point(globalLocation.x - screenBounds.x, globalLocation.y - screenBounds.y)
}

/**
 * Pure version of [locationOnDisplay] for testing — computes display-relative coordinates from raw values without
 * needing an AWT [Component].
 *
 * @param globalLocationX The component's global X coordinate on screen (from [Component.getLocationOnScreen]).
 * @param globalLocationY The component's global Y coordinate on screen.
 * @param screenBoundsX The X origin of the screen the component is on (from
 *   [java.awt.GraphicsConfiguration.getBounds]).
 * @param screenBoundsY The Y origin of the screen the component is on.
 * @return A [Point] containing display-relative coordinates.
 */
fun locationOnDisplay(globalLocationX: Int, globalLocationY: Int, screenBoundsX: Int, screenBoundsY: Int): Point =
    Point(globalLocationX - screenBoundsX, globalLocationY - screenBoundsY)

/**
 * Converts a rectangle from Compose coordinates (relative to the component's layout root) to screen-relative Compose
 * pixel coordinates, suitable for use with [androidx.compose.ui.window.PopupPositionProvider.calculatePosition].
 *
 * The conversion adds the component's display-relative position (scaled to Compose pixels) to the rectangle's
 * coordinates.
 *
 * @param ownerLocationOnDisplay The component's position relative to its current screen, in AWT pixels.
 * @param density The screen density (scale factor).
 * @return An [IntRect] in screen-relative Compose pixel coordinates.
 */
fun fromRelativeToScreen(rect: IntRect, ownerLocationOnDisplay: Point, density: Float): IntRect {
    val offsetX = (ownerLocationOnDisplay.x * density).fastRoundToInt()
    val offsetY = (ownerLocationOnDisplay.y * density).fastRoundToInt()
    return IntRect(
        left = offsetX + rect.left,
        top = offsetY + rect.top,
        right = offsetX + rect.right,
        bottom = offsetY + rect.bottom,
    )
}

/**
 * Converts a point from screen-relative Compose pixel coordinates to component-relative AWT pixel coordinates.
 *
 * This is the inverse of the position part of [fromRelativeToScreen]: it subtracts the component's display-relative
 * offset (scaled to Compose pixels), then converts from Compose pixels to AWT pixels by dividing by density.
 *
 * @param ownerLocationOnDisplay The component's position relative to its current screen, in AWT pixels.
 * @param density The screen density (scale factor).
 * @return A [Point] in component-relative AWT pixel coordinates.
 */
fun toComponentRelative(screenPoint: Point, ownerLocationOnDisplay: Point, density: Float): Point {
    val relativeX = screenPoint.x - (ownerLocationOnDisplay.x * density).fastRoundToInt()
    val relativeY = screenPoint.y - (ownerLocationOnDisplay.y * density).fastRoundToInt()
    return Point(floor(relativeX / density).toInt(), floor(relativeY / density).toInt())
}

/**
 * Converts a rectangle from Compose coordinates to screen-relative Compose pixel coordinates using the component's
 * actual screen position and density.
 *
 * Delegates to the pure [fromRelativeToScreen] function after extracting the component's location and density.
 */
fun IntRect.fromRelativeToScreen(component: Component): IntRect =
    fromRelativeToScreen(this, component.locationOnDisplay(), component.density())

/**
 * Converts a point from screen-relative Compose pixel coordinates to global AWT screen coordinates, suitable for use as
 * a [java.awt.Window] position.
 *
 * First converts to component-relative AWT pixels using [toComponentRelative], then uses
 * [SwingUtilities.convertPointToScreen] to get global screen coordinates.
 *
 * @param component The AWT component used as the coordinate reference.
 * @return A [Point] in global AWT screen coordinates.
 */
fun Point.fromCurrentScreenToGlobal(component: Component): Point {
    val awtPoint = toComponentRelative(this, component.locationOnDisplay(), component.density())
    SwingUtilities.convertPointToScreen(awtPoint, component)
    return awtPoint
}
