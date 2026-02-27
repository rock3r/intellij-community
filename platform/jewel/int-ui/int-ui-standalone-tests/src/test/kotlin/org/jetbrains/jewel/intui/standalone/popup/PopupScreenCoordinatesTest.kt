// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.jewel.intui.standalone.popup

import androidx.compose.ui.unit.IntRect
import java.awt.Point
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PopupScreenCoordinatesTest {

    @Nested
    inner class FromRelativeToScreenTest {
        @Test
        fun `boundsInRoot with density 2 and non-zero owner location`() {
            val rect = IntRect(left = 50, top = 100, right = 250, bottom = 300)
            val ownerLocation = Point(108, 208)
            val density = 2.0f

            val result = fromRelativeToScreen(rect, ownerLocation, density)

            // ownerLocation * density + rect bounds
            // x: 108 * 2 + 50 = 266, y: 208 * 2 + 100 = 516
            // right: 108 * 2 + 250 = 466, bottom: 208 * 2 + 300 = 716
            assertEquals(IntRect(left = 266, top = 516, right = 466, bottom = 716), result)
        }

        @Test
        fun `density 1 means no scaling, result is boundsInRoot plus ownerLocation`() {
            val rect = IntRect(left = 10, top = 20, right = 110, bottom = 120)
            val ownerLocation = Point(50, 60)
            val density = 1.0f

            val result = fromRelativeToScreen(rect, ownerLocation, density)

            assertEquals(IntRect(left = 60, top = 80, right = 160, bottom = 180), result)
        }

        @Test
        fun `ownerLocation at origin means result equals boundsInRoot`() {
            val rect = IntRect(left = 10, top = 20, right = 110, bottom = 120)
            val ownerLocation = Point(0, 0)
            val density = 2.0f

            val result = fromRelativeToScreen(rect, ownerLocation, density)

            assertEquals(rect, result)
        }
    }

    @Nested
    inner class ToComponentRelativeTest {
        @Test
        fun `converts screen-relative compose pixels to component-relative AWT pixels`() {
            val screenPoint = Point(266, 516)
            val ownerLocation = Point(108, 208)
            val density = 2.0f

            val result = toComponentRelative(screenPoint, ownerLocation, density)

            // relativeX = 266 - (108 * 2) = 266 - 216 = 50
            // relativeY = 516 - (208 * 2) = 516 - 416 = 100
            // awtX = floor(50 / 2) = 25, awtY = floor(100 / 2) = 50
            assertEquals(Point(25, 50), result)
        }

        @Test
        fun `density 1 means direct subtraction of owner location`() {
            val screenPoint = Point(150, 200)
            val ownerLocation = Point(50, 60)
            val density = 1.0f

            val result = toComponentRelative(screenPoint, ownerLocation, density)

            assertEquals(Point(100, 140), result)
        }

        @Test
        fun `ownerLocation at origin means result is compose-to-AWT scaling only`() {
            val screenPoint = Point(100, 200)
            val ownerLocation = Point(0, 0)
            val density = 2.0f

            val result = toComponentRelative(screenPoint, ownerLocation, density)

            assertEquals(Point(50, 100), result)
        }
    }

    @Nested
    inner class LocationOnDisplayTest {
        @Test
        fun `primary monitor with origin at 0,0`() {
            val result =
                locationOnDisplay(globalLocationX = 100, globalLocationY = 200, screenBoundsX = 0, screenBoundsY = 0)
            assertEquals(Point(100, 200), result)
        }

        @Test
        fun `secondary monitor offset to the right`() {
            val result =
                locationOnDisplay(
                    globalLocationX = 2020,
                    globalLocationY = 200,
                    screenBoundsX = 1920,
                    screenBoundsY = 0,
                )
            assertEquals(Point(100, 200), result)
        }

        @Test
        fun `monitor with negative screen bounds`() {
            val result =
                locationOnDisplay(
                    globalLocationX = -100,
                    globalLocationY = 200,
                    screenBoundsX = -1920,
                    screenBoundsY = 0,
                )
            assertEquals(Point(1820, 200), result)
        }
    }

    @Nested
    inner class RoundtripTest {
        @Test
        fun `fromRelativeToScreen position then toComponentRelative recovers original AWT position at density 2`() {
            val boundsInRoot = IntRect(left = 50, top = 100, right = 250, bottom = 300)
            val ownerLocation = Point(108, 208)
            val density = 2.0f

            val screenRect = fromRelativeToScreen(boundsInRoot, ownerLocation, density)
            val screenPoint = Point(screenRect.left, screenRect.top)

            val recovered = toComponentRelative(screenPoint, ownerLocation, density)

            // Original compose position is (50, 100), divided by density 2.0 -> (25, 50) AWT pixels
            assertEquals(Point(25, 50), recovered)
        }

        @Test
        fun `roundtrip at density 1`() {
            val boundsInRoot = IntRect(left = 30, top = 40, right = 130, bottom = 140)
            val ownerLocation = Point(200, 300)
            val density = 1.0f

            val screenRect = fromRelativeToScreen(boundsInRoot, ownerLocation, density)
            val screenPoint = Point(screenRect.left, screenRect.top)

            val recovered = toComponentRelative(screenPoint, ownerLocation, density)

            // At density 1, compose pixels == AWT pixels
            assertEquals(Point(30, 40), recovered)
        }

        @Test
        fun `roundtrip at density 1_5`() {
            // Use coordinates that are exact multiples of 1.5 to avoid rounding issues
            val boundsInRoot = IntRect(left = 60, top = 90, right = 360, bottom = 390)
            val ownerLocation = Point(100, 200)
            val density = 1.5f

            val screenRect = fromRelativeToScreen(boundsInRoot, ownerLocation, density)
            val screenPoint = Point(screenRect.left, screenRect.top)

            val recovered = toComponentRelative(screenPoint, ownerLocation, density)

            // Original compose position is (60, 90), divided by density 1.5 -> (40, 60) AWT pixels
            assertEquals(Point(40, 60), recovered)
        }
    }
}
