package org.jetbrains.jewel.foundation.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * A composable with explicitly controlled min and max intrinsic widths. [minWidth] is the min-content width (e.g.
 * widest single word). [maxWidth] is the max-content width (e.g. full unwrapped text). Both heights are fixed at
 * [fixedHeight].
 */
@Composable
private fun FixedIntrinsicsBox(minWidth: Int, maxWidth: Int, fixedHeight: Int, modifier: Modifier = Modifier) {
    Layout(
        content = {},
        modifier = modifier,
        measurePolicy =
            object : MeasurePolicy {
                override fun MeasureScope.measure(
                    measurables: List<Measurable>,
                    constraints: Constraints,
                ): MeasureResult {
                    val w = maxWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
                    val h = fixedHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
                    return layout(w, h) {}
                }

                override fun IntrinsicMeasureScope.minIntrinsicWidth(
                    measurables: List<IntrinsicMeasurable>,
                    height: Int,
                ) = minWidth

                override fun IntrinsicMeasureScope.maxIntrinsicWidth(
                    measurables: List<IntrinsicMeasurable>,
                    height: Int,
                ) = maxWidth

                override fun IntrinsicMeasureScope.minIntrinsicHeight(
                    measurables: List<IntrinsicMeasurable>,
                    width: Int,
                ) = fixedHeight

                override fun IntrinsicMeasureScope.maxIntrinsicHeight(
                    measurables: List<IntrinsicMeasurable>,
                    width: Int,
                ) = fixedHeight
            },
    )
}

private val NoBorder = Color.Transparent
private val BorderColor = Color.Black

public class BasicTableLayoutTest {
    @get:Rule public val composeRule = createComposeRule()

    // =====================================================================
    // Width distribution: filling available space
    // =====================================================================

    @Test
    public fun `table fills available width when content is narrower`() {
        composeRule.setContent {
            Box(Modifier.requiredWidth(600.dp)) {
                BasicTableLayout(
                    rowCount = 1,
                    columnCount = 2,
                    cellBorderColor = NoBorder,
                    modifier = Modifier.testTag("table"),
                    rows = listOf(listOf({ Box(Modifier.width(50.dp)) }, { Box(Modifier.width(50.dp)) })),
                )
            }
        }
        composeRule.onNodeWithTag("table").assertWidthIsEqualTo(600.dp)
    }

    @Test
    public fun `table does not exceed available width when content is wider`() {
        composeRule.setContent {
            Box(Modifier.requiredWidth(200.dp)) {
                BasicTableLayout(
                    rowCount = 1,
                    columnCount = 2,
                    cellBorderColor = NoBorder,
                    modifier = Modifier.testTag("table"),
                    rows = listOf(listOf({ Box(Modifier.width(300.dp)) }, { Box(Modifier.width(300.dp)) })),
                )
            }
        }
        composeRule.onNodeWithTag("table").assertWidthIsEqualTo(200.dp)
    }

    // =====================================================================
    // Width distribution: proportional to intrinsic widths
    // =====================================================================

    @Test
    public fun `extra space is distributed proportionally to intrinsic column widths`() {
        // 2-column table where column 0 is 100dp wide and column 1 is 300dp wide.
        // Available = 600dp, no borders.
        // Total intrinsic = 400dp; extra = 200dp.
        // Column 0 gets 200 * (100/400) = 50dp extra → 150dp total
        // Column 1 gets 200 * (300/400) = 150dp extra → 450dp total
        composeRule.setContent {
            Box(Modifier.requiredWidth(600.dp)) {
                BasicTableLayout(
                    rowCount = 1,
                    columnCount = 2,
                    cellBorderColor = NoBorder,
                    cellBorderWidth = 0.dp,
                    rows =
                        listOf(
                            listOf(
                                { Box(Modifier.testTag("col0").size(100.dp, 10.dp)) },
                                { Box(Modifier.testTag("col1").size(300.dp, 10.dp)) },
                            )
                        ),
                )
            }
        }
        composeRule.onNodeWithTag("col0").assertWidthIsEqualTo(150.dp)
        composeRule.onNodeWithTag("col1").assertWidthIsEqualTo(450.dp)
    }

    @Test
    public fun `all-empty columns share extra space equally`() {
        // 3-column table with no content and no minColumnWidth.
        // With available = 600dp and no borders, each column should be 200dp.
        composeRule.setContent {
            Box(Modifier.requiredWidth(600.dp)) {
                BasicTableLayout(
                    rowCount = 1,
                    columnCount = 3,
                    cellBorderColor = NoBorder,
                    cellBorderWidth = 0.dp,
                    rows =
                        listOf(
                            listOf(
                                { Box(Modifier.testTag("col0")) },
                                { Box(Modifier.testTag("col1")) },
                                { Box(Modifier.testTag("col2")) },
                            )
                        ),
                )
            }
        }
        composeRule.onNodeWithTag("col0").assertWidthIsEqualTo(200.dp)
        composeRule.onNodeWithTag("col1").assertWidthIsEqualTo(200.dp)
        composeRule.onNodeWithTag("col2").assertWidthIsEqualTo(200.dp)
    }

    // =====================================================================
    // Minimum column width
    // =====================================================================

    @Test
    public fun `minColumnWidth ensures empty columns have a minimum size`() {
        // 2-column table where column 0 is empty and column 1 has 200dp content.
        // minColumnWidth = 50dp.
        // After clamping: col0 = 50dp, col1 = 200dp. Total = 250dp.
        // Available = 600dp. Extra = 350dp.
        // Proportional: col0 += 350 * (50/250) = 70dp → 120dp; col1 += 350 * (200/250) = 280dp → 480dp.
        composeRule.setContent {
            Box(Modifier.requiredWidth(600.dp)) {
                BasicTableLayout(
                    rowCount = 1,
                    columnCount = 2,
                    cellBorderColor = NoBorder,
                    cellBorderWidth = 0.dp,
                    minColumnWidth = 50.dp,
                    rows =
                        listOf(
                            listOf(
                                { Box(Modifier.testTag("col0")) },
                                { Box(Modifier.testTag("col1").size(200.dp, 10.dp)) },
                            )
                        ),
                )
            }
        }
        composeRule.onNodeWithTag("col0").assertWidthIsEqualTo(120.dp)
        composeRule.onNodeWithTag("col1").assertWidthIsEqualTo(480.dp)
    }

    @Test
    public fun `minColumnWidth is respected even when content is narrower`() {
        // 2-column table where both columns have only 10dp content.
        // minColumnWidth = 80dp. Available = 300dp, no borders.
        // After clamping: both columns = 80dp. Total = 160dp.
        // Extra = 140dp; equal proportional: each gets 70dp extra → 150dp each.
        composeRule.setContent {
            Box(Modifier.requiredWidth(300.dp)) {
                BasicTableLayout(
                    rowCount = 1,
                    columnCount = 2,
                    cellBorderColor = NoBorder,
                    cellBorderWidth = 0.dp,
                    minColumnWidth = 80.dp,
                    rows =
                        listOf(
                            listOf(
                                { Box(Modifier.testTag("col0").size(10.dp, 10.dp)) },
                                { Box(Modifier.testTag("col1").size(10.dp, 10.dp)) },
                            )
                        ),
                )
            }
        }
        composeRule.onNodeWithTag("col0").assertWidthIsEqualTo(150.dp)
        composeRule.onNodeWithTag("col1").assertWidthIsEqualTo(150.dp)
    }

    // =====================================================================
    // Borders
    // =====================================================================

    @Test
    public fun `borders are included in total table width`() {
        // 2-column table with 10dp borders.
        // Columns have 100dp content each; total intrinsic = 100 + 100 + 3*10 = 230dp.
        // Available = 600dp; extra = 370dp.
        // Proportional: each column gets 370 * (100/200) = 185dp extra.
        // Final column widths: 100 + 185 = 285dp each.
        // Total table width = 285 + 285 + 3*10 = 600dp. ✓
        composeRule.setContent {
            Box(Modifier.requiredWidth(600.dp)) {
                BasicTableLayout(
                    rowCount = 1,
                    columnCount = 2,
                    cellBorderColor = BorderColor,
                    cellBorderWidth = 10.dp,
                    modifier = Modifier.testTag("table"),
                    rows =
                        listOf(
                            listOf(
                                { Box(Modifier.testTag("col0").size(100.dp, 10.dp)) },
                                { Box(Modifier.testTag("col1").size(100.dp, 10.dp)) },
                            )
                        ),
                )
            }
        }
        composeRule.onNodeWithTag("table").assertWidthIsEqualTo(600.dp)
        // With equal 100dp starting widths, extra space splits evenly: 285dp each
        composeRule.onNodeWithTag("col0").assertWidthIsEqualTo(285.dp)
        composeRule.onNodeWithTag("col1").assertWidthIsEqualTo(285.dp)
    }

    @Test
    public fun `cell positions account for border width`() {
        // 2-column table with 5dp borders and 100dp columns.
        // col0 left edge = 5dp (border), col1 left edge = 5 + 100 + 5 = 110dp.
        // But first, the table fills 300dp available. Intrinsic = 100 + 100 + 3*5 = 215dp.
        // Extra = 85dp; each column grows by ~42dp (42, 43 due to rounding).
        // Let's use exact-fit so positions are predictable: available = 215dp.
        composeRule.setContent {
            Box(Modifier.requiredWidth(215.dp)) {
                BasicTableLayout(
                    rowCount = 1,
                    columnCount = 2,
                    cellBorderColor = BorderColor,
                    cellBorderWidth = 5.dp,
                    rows =
                        listOf(
                            listOf(
                                { Box(Modifier.testTag("col0").requiredSize(100.dp, 10.dp)) },
                                { Box(Modifier.testTag("col1").requiredSize(100.dp, 10.dp)) },
                            )
                        ),
                )
            }
        }
        // col0 starts at x=5dp (left border)
        composeRule.onNodeWithTag("col0").assertLeftPositionInRootIsEqualTo(5.dp)
        // col1 starts at x=5 + 100 + 5 = 110dp
        composeRule.onNodeWithTag("col1").assertLeftPositionInRootIsEqualTo(110.dp)
    }

    // =====================================================================
    // Row heights
    // =====================================================================

    @Test
    public fun `row height is the maximum of all cell heights in that row`() {
        // 3-column row where cells are 20dp, 50dp, and 30dp tall.
        // Row height should be 50dp.
        composeRule.setContent {
            Box(Modifier.requiredWidth(600.dp)) {
                BasicTableLayout(
                    rowCount = 1,
                    columnCount = 3,
                    cellBorderColor = NoBorder,
                    rows =
                        listOf(
                            listOf(
                                { Box(Modifier.testTag("col0").size(50.dp, 20.dp)) },
                                { Box(Modifier.testTag("col1").size(50.dp, 50.dp)) },
                                { Box(Modifier.testTag("col2").size(50.dp, 30.dp)) },
                            )
                        ),
                )
            }
        }
        // All cells in the row share the same height (the max)
        composeRule.onNodeWithTag("col0").assertHeightIsEqualTo(50.dp)
        composeRule.onNodeWithTag("col1").assertHeightIsEqualTo(50.dp)
        composeRule.onNodeWithTag("col2").assertHeightIsEqualTo(50.dp)
    }

    // =====================================================================
    // Multi-row
    // =====================================================================

    @Test
    public fun `column widths are consistent across rows`() {
        // 2-column, 2-row table: row 0 has 100dp/200dp, row 1 has 50dp/250dp.
        // Intrinsic widths: col0 = max(100, 50) = 100dp, col1 = max(200, 250) = 250dp.
        // Available = 600dp, no borders. Total intrinsic = 350dp; extra = 250dp.
        // Proportional: col0 += 250*(100/350) ≈ 71dp → ~171dp; col1 += ~179dp → ~429dp.
        // (rounding gives last column remainder so col0+col1 = 600 exactly)
        // Note: size() (not requiredSize()) is used so cells respect the fixed column constraints,
        // making their semantic bounds equal to the allocated column width.
        composeRule.setContent {
            Box(Modifier.requiredWidth(600.dp)) {
                BasicTableLayout(
                    rowCount = 2,
                    columnCount = 2,
                    cellBorderColor = NoBorder,
                    cellBorderWidth = 0.dp,
                    rows =
                        listOf(
                            listOf(
                                { Box(Modifier.testTag("r0c0").size(100.dp, 10.dp)) },
                                { Box(Modifier.testTag("r0c1").size(200.dp, 10.dp)) },
                            ),
                            listOf(
                                { Box(Modifier.testTag("r1c0").size(50.dp, 10.dp)) },
                                { Box(Modifier.testTag("r1c1").size(250.dp, 10.dp)) },
                            ),
                        ),
                )
            }
        }
        // Both rows must see the same column widths (compare raw pixel widths)
        val r0c0Width = composeRule.onNodeWithTag("r0c0").fetchSemanticsNode().boundsInRoot.width
        val r1c0Width = composeRule.onNodeWithTag("r1c0").fetchSemanticsNode().boundsInRoot.width
        assertEquals("Column 0 width must be the same in both rows", r0c0Width, r1c0Width, 0.5f)

        val r0c1Width = composeRule.onNodeWithTag("r0c1").fetchSemanticsNode().boundsInRoot.width
        val r1c1Width = composeRule.onNodeWithTag("r1c1").fetchSemanticsNode().boundsInRoot.width
        assertEquals("Column 1 width must be the same in both rows", r0c1Width, r1c1Width, 0.5f)
    }

    // =====================================================================
    // Single column / single row edge cases
    // =====================================================================

    @Test
    public fun `single-column table fills available width`() {
        composeRule.setContent {
            Box(Modifier.requiredWidth(400.dp)) {
                BasicTableLayout(
                    rowCount = 1,
                    columnCount = 1,
                    cellBorderColor = NoBorder,
                    cellBorderWidth = 0.dp,
                    modifier = Modifier.testTag("table"),
                    rows = listOf(listOf({ Box(Modifier.testTag("cell").width(50.dp)) })),
                )
            }
        }
        composeRule.onNodeWithTag("table").assertWidthIsEqualTo(400.dp)
        composeRule.onNodeWithTag("cell").assertWidthIsEqualTo(400.dp)
    }

    @Test
    public fun `single-row table fills available width`() {
        composeRule.setContent {
            Box(Modifier.requiredWidth(500.dp)) {
                BasicTableLayout(
                    rowCount = 1,
                    columnCount = 3,
                    cellBorderColor = NoBorder,
                    modifier = Modifier.testTag("table"),
                    rows =
                        listOf(
                            listOf(
                                { Box(Modifier.testTag("col0").width(50.dp)) },
                                { Box(Modifier.testTag("col1").width(50.dp)) },
                                { Box(Modifier.testTag("col2").width(50.dp)) },
                            )
                        ),
                )
            }
        }
        composeRule.onNodeWithTag("table").assertWidthIsEqualTo(500.dp)
    }

    // =====================================================================
    // Scale-down: min-content floors
    // =====================================================================

    @Test
    public fun `scale-down respects min-content widths as floor`() {
        // 2-column table, available = 200dp, no borders.
        // col0: min-content = 40dp, max-content = 40dp (single required word, can't shrink)
        // col1: min-content = 60dp, max-content = 460dp (long wrappable content)
        // Total max-content = 500dp > 200dp (available) → scale-down path.
        // Total min-content = 40 + 60 = 100dp ≤ 200dp → floors fit.
        // Surplus after floors = 200 - 100 = 100dp.
        // Capacities: col0 = 0, col1 = 400dp. All surplus → col1.
        // Final: col0 = 40dp, col1 = 160dp.
        val density = composeRule.density
        val col0MinPx = with(density) { 40.dp.roundToPx() }
        val col0MaxPx = col0MinPx
        val col1MinPx = with(density) { 60.dp.roundToPx() }
        val col1MaxPx = with(density) { 460.dp.roundToPx() }
        val heightPx = with(density) { 10.dp.roundToPx() }

        composeRule.setContent {
            Box(Modifier.requiredWidth(200.dp)) {
                BasicTableLayout(
                    rowCount = 1,
                    columnCount = 2,
                    cellBorderColor = NoBorder,
                    cellBorderWidth = 0.dp,
                    rows =
                        listOf(
                            listOf(
                                {
                                    FixedIntrinsicsBox(
                                        minWidth = col0MinPx,
                                        maxWidth = col0MaxPx,
                                        fixedHeight = heightPx,
                                        modifier = Modifier.testTag("col0"),
                                    )
                                },
                                {
                                    FixedIntrinsicsBox(
                                        minWidth = col1MinPx,
                                        maxWidth = col1MaxPx,
                                        fixedHeight = heightPx,
                                        modifier = Modifier.testTag("col1"),
                                    )
                                },
                            )
                        ),
                )
            }
        }
        // col0 gets exactly its min-content (no capacity to absorb surplus)
        composeRule.onNodeWithTag("col0").assertWidthIsEqualTo(40.dp)
        // col1 gets its min-content + all surplus: 60 + 100 = 160dp
        composeRule.onNodeWithTag("col1").assertWidthIsEqualTo(160.dp)
    }

    @Test
    public fun `scale-down distributes surplus proportionally to capacity when columns can expand`() {
        // 2-column table, available = 200dp, no borders.
        // col0: min = 40dp, max = 140dp → capacity = 100dp
        // col1: min = 60dp, max = 160dp → capacity = 100dp
        // Total max = 300dp > 200dp → scale-down path.
        // Total min = 100dp ≤ 200dp → floors fit.
        // Surplus = 100dp. Both capacities are equal (100dp each) → each gets 50dp.
        // Final: col0 = 40 + 50 = 90dp, col1 = 60 + 50 = 110dp.
        val density = composeRule.density
        val heightPx = with(density) { 10.dp.roundToPx() }

        composeRule.setContent {
            Box(Modifier.requiredWidth(200.dp)) {
                BasicTableLayout(
                    rowCount = 1,
                    columnCount = 2,
                    cellBorderColor = NoBorder,
                    cellBorderWidth = 0.dp,
                    rows =
                        listOf(
                            listOf(
                                {
                                    FixedIntrinsicsBox(
                                        minWidth = with(density) { 40.dp.roundToPx() },
                                        maxWidth = with(density) { 140.dp.roundToPx() },
                                        fixedHeight = heightPx,
                                        modifier = Modifier.testTag("col0"),
                                    )
                                },
                                {
                                    FixedIntrinsicsBox(
                                        minWidth = with(density) { 60.dp.roundToPx() },
                                        maxWidth = with(density) { 160.dp.roundToPx() },
                                        fixedHeight = heightPx,
                                        modifier = Modifier.testTag("col1"),
                                    )
                                },
                            )
                        ),
                )
            }
        }
        composeRule.onNodeWithTag("col0").assertWidthIsEqualTo(90.dp)
        composeRule.onNodeWithTag("col1").assertWidthIsEqualTo(110.dp)
    }
}
