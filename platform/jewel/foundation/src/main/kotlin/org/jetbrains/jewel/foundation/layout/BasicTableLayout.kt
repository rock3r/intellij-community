package org.jetbrains.jewel.foundation.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import org.jetbrains.jewel.foundation.modifier.thenIf

/**
 * A simple table that fills the available horizontal space and sizes each column proportionally to its intrinsic
 * content width. All columns are guaranteed to be at least [minColumnWidth] wide.
 *
 * When the total max-content width is less than the available width, the extra space is distributed among columns
 * proportionally to their max-content widths. This ensures columns with more content receive a fair share of the extra
 * space, while [minColumnWidth] prevents any column from collapsing to zero — similar to how web browsers render tables
 * by default.
 *
 * When the total max-content width exceeds the available width, each column is first given its min-content width (the
 * minimum width needed to avoid clipping, clamped to [minColumnWidth]). If the sum of min-content widths fits in the
 * available space, the remaining space is distributed proportionally to each column's extra capacity (max-content minus
 * min-content). If even the min-content widths don't fit, all columns are scaled down proportionally as a last resort.
 *
 * When the available width is unbounded (e.g. inside a scrolling container), the table takes only as much horizontal
 * space as its columns require (their clamped max-content widths plus borders).
 *
 * Cells **must** only contain one top-level component. If you need your cells to contain more than one, wrap your cell
 * content in a [`Box`][Box], [`Column`][androidx.compose.foundation.layout.Column],
 * [`Row`][androidx.compose.foundation.layout.Row], etc.
 *
 * Incoming height constraints are ignored. The table will always take up as much vertical room as it needs. If you want
 * to constrain the table height consider wrapping it in a
 * [`VerticallyScrollableContainer`][org.jetbrains.jewel.ui.component.VerticallyScrollableContainer].
 *
 * @param rowCount The number of rows this table has.
 * @param columnCount The number of columns this table has.
 * @param cellBorderColor The color of the cell borders. Set to [Color.Unspecified] to avoid drawing the borders — in
 *   which case, the [cellBorderWidth] acts as a padding.
 * @param modifier Modifier to apply to the table.
 * @param cellBorderWidth The width of the table's borders.
 * @param minColumnWidth The minimum width each column must have, regardless of its content. Defaults to zero, meaning
 *   empty columns may collapse. Set this to a positive value to ensure all columns are always visible even when their
 *   content is empty or very narrow.
 * @param rows The rows that make up the table. Each row is a list of composables, one per row cell.
 */
@Suppress("KDocUnresolvedReference", "ComposableParamOrder")
@Composable
public fun BasicTableLayout(
    rowCount: Int,
    columnCount: Int,
    cellBorderColor: Color,
    modifier: Modifier = Modifier,
    cellBorderWidth: Dp = 1.dp,
    minColumnWidth: Dp = 0.dp,
    rows: List<List<@Composable () -> Unit>>,
) {
    var rowHeights by remember { mutableStateOf(emptyList<Int>()) }
    var columnWidths by remember { mutableStateOf(emptyList<Int>()) }

    Layout(
        modifier =
            modifier.thenIf(rowHeights.size == rowCount && columnWidths.size == columnCount) {
                drawTableBorders(cellBorderColor, cellBorderWidth, rowHeights, columnWidths)
            },
        content = { rows.forEach { row -> row.forEach { cell -> cell() } } },
        measurePolicy = { measurables, incomingConstraints ->
            require(rows.size == rowCount) { "Found ${rows.size} rows, but expected $rowCount." }
            require(measurables.size == rowCount * columnCount) {
                "Found ${measurables.size} cells, but expected ${rowCount * columnCount}."
            }

            // Measure both max-content and min-content widths per column.
            // max-content = natural width when unconstrained (full content, no wrapping).
            // min-content = minimum width without clipping (widest single word for text).
            val maxContentWidths = IntArray(columnCount)
            val minContentWidths = IntArray(columnCount)
            val measurablesByRow = measurables.chunked(columnCount)
            for ((rowIndex, rowCells) in rows.withIndex()) {
                require(rowCells.size == columnCount) {
                    "Row $rowIndex contains ${rowCells.size} cells, but it should have $columnCount cells."
                }

                for ((columnIndex, _) in rowCells.withIndex()) {
                    val measurable = measurablesByRow[rowIndex][columnIndex]
                    maxContentWidths[columnIndex] =
                        max(maxContentWidths[columnIndex], measurable.maxIntrinsicWidth(height = Int.MAX_VALUE))
                    minContentWidths[columnIndex] =
                        max(minContentWidths[columnIndex], measurable.minIntrinsicWidth(height = Int.MAX_VALUE))
                }
            }

            // Apply the minimum column width floor to both sets of widths.
            val minColumnWidthPx = minColumnWidth.roundToPx()
            for (i in 0 until columnCount) {
                maxContentWidths[i] = max(maxContentWidths[i], minColumnWidthPx)
                minContentWidths[i] = max(minContentWidths[i], minColumnWidthPx)
            }

            // The available width we can assign to cells is equal to the max width from the
            // incoming constraints, minus the vertical borders applied between columns and to
            // the sides of the table
            val cellBorderWidthPx = cellBorderWidth.roundToPx()
            val totalHorizontalBordersWidth = cellBorderWidthPx * (columnCount + 1)
            val totalMaxContentWidth = maxContentWidths.sum()
            val maxContentTableWidth = totalMaxContentWidth + totalHorizontalBordersWidth
            val availableWidth = incomingConstraints.maxWidth

            // finalWidths holds the actual column widths we'll use for layout.
            val finalWidths = maxContentWidths.copyOf()

            val tableWidth: Int

            when {
                availableWidth == Constraints.Infinity -> {
                    // Unbounded container: use max-content column sizes as-is
                    tableWidth = maxContentTableWidth
                }
                maxContentTableWidth <= availableWidth -> {
                    // We have more room than needed: expand columns to fill the available width.
                    // Extra space is distributed proportionally to each column's max-content width,
                    // so content-heavy columns receive a fair share while narrow columns are not
                    // starved entirely.
                    val extraWidth = availableWidth - maxContentTableWidth
                    if (extraWidth > 0 && columnCount > 0) {
                        if (totalMaxContentWidth > 0) {
                            // Proportional distribution: column gets extra * (itsWidth / total)
                            var distributed = 0
                            for (i in 0 until columnCount - 1) {
                                val extra = (extraWidth.toLong() * finalWidths[i] / totalMaxContentWidth).toInt()
                                finalWidths[i] += extra
                                distributed += extra
                            }
                            // Give any remaining pixels due to integer rounding to the last column
                            finalWidths[columnCount - 1] += extraWidth - distributed
                        } else {
                            // All columns are empty; distribute the extra space equally
                            val extraPerColumn = extraWidth / columnCount
                            val remainder = extraWidth % columnCount
                            for (i in 0 until columnCount) {
                                finalWidths[i] += extraPerColumn
                            }
                            finalWidths[columnCount - 1] += remainder
                        }
                    }
                    tableWidth = availableWidth
                }
                else -> {
                    // Max-content widths don't fit. Use min-content widths as floors (CSS table
                    // auto-layout: each column is guaranteed at least its widest unbreakable unit).
                    val totalMinContentWidth = minContentWidths.sum()
                    val minFitWidth = totalMinContentWidth + totalHorizontalBordersWidth

                    if (minFitWidth <= availableWidth) {
                        // All min-content floors fit: start with floors, then distribute the
                        // remaining surplus proportionally to each column's capacity
                        // (max-content minus its floor), so wider columns get more of the bonus.
                        val surplus = availableWidth - minFitWidth
                        val capacities =
                            IntArray(columnCount) { i -> max(0, maxContentWidths[i] - minContentWidths[i]) }
                        val totalCapacity = capacities.sum()

                        for (i in 0 until columnCount) {
                            finalWidths[i] = minContentWidths[i]
                        }

                        if (surplus > 0) {
                            if (totalCapacity > 0) {
                                var distributed = 0
                                for (i in 0 until columnCount - 1) {
                                    val extra = (surplus.toLong() * capacities[i] / totalCapacity).toInt()
                                    finalWidths[i] += extra
                                    distributed += extra
                                }
                                finalWidths[columnCount - 1] += surplus - distributed
                            } else {
                                // All columns have max == min; distribute surplus equally
                                val extraPerColumn = surplus / columnCount
                                val remainder = surplus % columnCount
                                for (i in 0 until columnCount) {
                                    finalWidths[i] += extraPerColumn
                                }
                                finalWidths[columnCount - 1] += remainder
                            }
                        }
                        tableWidth = availableWidth
                    } else {
                        // Even min-content widths don't fit; scale them down proportionally.
                        // This is a last resort — some content will inevitably be clipped or wrap.
                        // Distribute exactly (availableWidth - borders) pixels across columns
                        // so the table never exceeds the available width.
                        val targetColumnSum = max(0, availableWidth - totalHorizontalBordersWidth)
                        if (totalMinContentWidth > 0) {
                            val scaleRatio = targetColumnSum.toFloat() / totalMinContentWidth
                            var scaledTotal = 0
                            for (i in 0 until columnCount - 1) {
                                finalWidths[i] = (minContentWidths[i] * scaleRatio).toInt()
                                scaledTotal += finalWidths[i]
                            }
                            // Give any remaining pixels due to integer truncation to the last column
                            finalWidths[columnCount - 1] = max(0, targetColumnSum - scaledTotal)
                        } else {
                            // All min-content widths are zero; distribute equally
                            val perColumn = targetColumnSum / columnCount
                            val remainder = targetColumnSum % columnCount
                            for (i in 0 until columnCount) finalWidths[i] = perColumn
                            finalWidths[columnCount - 1] += remainder
                        }
                        tableWidth = availableWidth
                    }
                }
            }
            columnWidths = finalWidths.toList()

            // The height of each row is the maximum intrinsic height of their cells, calculated
            // from the (possibly scaled) intrinsic column widths we just computed
            val intrinsicRowHeights = IntArray(rowCount)
            var tableHeight = 0
            measurablesByRow.mapIndexed { rowIndex, rowMeasurables ->
                var maxCellHeight = 0
                for ((columnIndex, cellMeasurable) in rowMeasurables.withIndex()) {
                    val columnWidth = columnWidths[columnIndex]
                    val cellHeight = cellMeasurable.maxIntrinsicHeight(width = columnWidth)
                    maxCellHeight = max(maxCellHeight, cellHeight)
                }

                tableHeight += maxCellHeight
                intrinsicRowHeights[rowIndex] = maxCellHeight
            }
            rowHeights = intrinsicRowHeights.toList()

            // Add the horizontal borders drawn between rows and on top and bottom of the table
            tableHeight += cellBorderWidthPx * (rowCount + 1)

            // Measure all cells, using the fixed constraints we calculated for each row and column
            val placeables =
                measurables.chunked(columnCount).mapIndexed { rowIndex, cellMeasurables ->
                    cellMeasurables.mapIndexed { columnIndex, cellMeasurable ->
                        val cellConstraints = Constraints.fixed(columnWidths[columnIndex], rowHeights[rowIndex])
                        cellMeasurable.measure(cellConstraints)
                    }
                }

            layout(tableWidth, tableHeight) {
                // Place cells. We start by leaving space for the top and start-side borders
                var y = cellBorderWidthPx

                placeables.forEachIndexed { _, cellPlaceables ->
                    var x = cellBorderWidthPx

                    var rowHeight = 0
                    cellPlaceables.forEach { cellPlaceable ->
                        cellPlaceable.placeRelative(x, y)
                        x += cellBorderWidthPx
                        x += cellPlaceable.width
                        rowHeight = cellPlaceable.height.coerceAtLeast(rowHeight)
                    }

                    y += cellBorderWidthPx
                    y += rowHeight
                }
            }
        },
    )
}

private fun Modifier.drawTableBorders(
    cellBorderColor: Color,
    cellBorderWidth: Dp,
    rowHeights: List<Int>,
    columnWidths: List<Int>,
) = drawBehind {
    val borderWidthPx = cellBorderWidth.toPx()
    val halfBorderWidthPx = borderWidthPx / 2f

    // First, draw the outer border
    drawRect(
        color = cellBorderColor,
        topLeft = Offset(halfBorderWidthPx, halfBorderWidthPx),
        size = Size(size.width - borderWidthPx, size.height - borderWidthPx),
        style = Stroke(width = borderWidthPx),
    )

    // Then, draw all horizontal borders below rows.
    // No need to draw the last horizontal border as it's covered by the border rect
    var y = halfBorderWidthPx
    val endX = size.width - borderWidthPx

    for (i in 0 until rowHeights.lastIndex) {
        y += rowHeights[i].toFloat() + borderWidthPx
        drawLine(
            color = cellBorderColor,
            start = Offset(halfBorderWidthPx, y),
            end = Offset(endX, y),
            strokeWidth = borderWidthPx,
        )
    }

    // Lastly, draw all vertical borders to the end of columns
    // (minus the last one, as before)
    var x = halfBorderWidthPx
    val endY = size.height - borderWidthPx

    for (i in 0 until columnWidths.lastIndex) {
        x += columnWidths[i].toFloat() + borderWidthPx
        drawLine(
            color = cellBorderColor,
            start = Offset(x, halfBorderWidthPx),
            end = Offset(x, endY),
            strokeWidth = borderWidthPx,
        )
    }
}
