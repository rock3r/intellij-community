// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.intui.standalone.popup

import java.awt.GraphicsEnvironment
import java.awt.Point
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PopupScreenCoordinatesAwtTest {

    private lateinit var frame: JFrame
    private lateinit var panel: JPanel

    @BeforeEach
    fun setUp() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Skipping AWT test in headless environment")

        SwingUtilities.invokeAndWait {
            frame =
                JFrame("Test Frame").apply {
                    setSize(400, 300)
                    setLocation(100, 100)
                }
            panel = JPanel()
            frame.contentPane.add(panel)
            frame.pack()
            frame.setSize(400, 300)
            frame.isVisible = true
        }

        // Give AWT time to lay out
        Thread.sleep(200)
    }

    @AfterEach
    fun tearDown() {
        if (::frame.isInitialized) {
            SwingUtilities.invokeAndWait {
                frame.isVisible = false
                frame.dispose()
            }
        }
    }

    @Test
    fun `fromCurrentScreenToGlobal converts compose pixels to global AWT screen coordinates`() {
        SwingUtilities.invokeAndWait {
            val panelLocationOnScreen = panel.locationOnScreen
            val density = panel.density()

            // A point at (0, 0) in screen-relative compose space of the component
            // should map to the component's own location on screen
            val ownerLocation = panel.locationOnDisplay()
            val composePoint = Point((ownerLocation.x * density).toInt(), (ownerLocation.y * density).toInt())

            val globalPoint = composePoint.fromCurrentScreenToGlobal(panel)

            // The result should be the panel's screen position (the compose offset cancels out)
            assertEquals(panelLocationOnScreen.x, globalPoint.x, "Global X should match panel's screen X")
            assertEquals(panelLocationOnScreen.y, globalPoint.y, "Global Y should match panel's screen Y")
        }
    }

    @Test
    fun `fromCurrentScreenToGlobal with offset produces correct screen position`() {
        SwingUtilities.invokeAndWait {
            val panelLocationOnScreen = panel.locationOnScreen
            val density = panel.density()
            val ownerLocation = panel.locationOnDisplay()

            // An offset of (50, 30) Compose pixels from the component's screen position
            val offsetComposeX = 50
            val offsetComposeY = 30
            val composePoint =
                Point(
                    (ownerLocation.x * density).toInt() + offsetComposeX,
                    (ownerLocation.y * density).toInt() + offsetComposeY,
                )

            val globalPoint = composePoint.fromCurrentScreenToGlobal(panel)

            // Expected: panel's location + offset converted to AWT pixels
            val expectedX = panelLocationOnScreen.x + kotlin.math.floor(offsetComposeX / density).toInt()
            val expectedY = panelLocationOnScreen.y + kotlin.math.floor(offsetComposeY / density).toInt()

            assertEquals(expectedX, globalPoint.x, "Global X with offset")
            assertEquals(expectedY, globalPoint.y, "Global Y with offset")
        }
    }

    @Test
    fun `density returns positive value from real graphics configuration`() {
        SwingUtilities.invokeAndWait {
            val density = panel.density()
            assert(density > 0f) { "Density should be positive, got $density" }
        }
    }

    @Test
    fun `locationOnDisplay returns non-negative coordinates for primary screen`() {
        SwingUtilities.invokeAndWait {
            val location = panel.locationOnDisplay()
            // On the primary screen, display-relative coordinates should be non-negative
            // (unless the frame is positioned off-screen, which we don't do in setUp)
            assert(location.x >= 0) { "Display-relative X should be non-negative, got ${location.x}" }
            assert(location.y >= 0) { "Display-relative Y should be non-negative, got ${location.y}" }
        }
    }
}
