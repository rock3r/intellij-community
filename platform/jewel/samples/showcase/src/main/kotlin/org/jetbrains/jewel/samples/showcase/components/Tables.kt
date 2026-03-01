// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the
// Apache 2.0 license.
package org.jetbrains.jewel.samples.showcase.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.layout.BasicTableLayout
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.GroupHeader
import org.jetbrains.jewel.ui.component.Text

@Composable
public fun Tables(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        GroupHeader("Balanced columns (equal content width)")
        BalancedTable()

        GroupHeader("Skewed columns (one column dominates)")
        SkewedTable()

        GroupHeader("Skewed columns with minColumnWidth = 48dp")
        SkewedTableWithMinWidth()

        GroupHeader("Empty columns")
        EmptyColumnsTable()

        GroupHeader("Many columns")
        ManyColumnsTable()
    }
}

/** A table where all columns have roughly the same amount of content. */
@Composable
private fun BalancedTable() {
    val borderColor = JewelTheme.globalColors.borders.normal
    BasicTableLayout(
        rowCount = 4,
        columnCount = 3,
        cellBorderColor = borderColor,
        modifier = Modifier.fillMaxWidth(),
        rows =
            listOf(
                listOf(
                    { TableCell("Name", fontWeight = FontWeight.Bold) },
                    { TableCell("Role", fontWeight = FontWeight.Bold) },
                    { TableCell("Status", fontWeight = FontWeight.Bold) },
                ),
                listOf({ TableCell("Alice") }, { TableCell("Engineer") }, { TableCell("Active") }),
                listOf({ TableCell("Bob") }, { TableCell("Designer") }, { TableCell("On leave") }),
                listOf({ TableCell("Carol") }, { TableCell("Manager") }, { TableCell("Active") }),
            ),
    )
}

/**
 * A table where the middle column has much more content than the others, reproducing the common Markdown table
 * rendering issue where a "Phase | What | Status" table collapses the narrow columns.
 */
@Composable
private fun SkewedTable() {
    val borderColor = JewelTheme.globalColors.borders.normal
    BasicTableLayout(
        rowCount = 4,
        columnCount = 3,
        cellBorderColor = borderColor,
        modifier = Modifier.fillMaxWidth(),
        rows =
            listOf(
                listOf(
                    { TableCell("Phase", fontWeight = FontWeight.Bold) },
                    { TableCell("What", fontWeight = FontWeight.Bold) },
                    { TableCell("Done", fontWeight = FontWeight.Bold) },
                ),
                listOf(
                    { TableCell("1") },
                    { TableCell("Intercept grep/find/ls — same denyRead + outside-project-root prompt as read") },
                    { TableCell("✓") },
                ),
                listOf(
                    { TableCell("2") },
                    {
                        TableCell(
                            "Deny-by-default catch-all — any unrecognized toolName gets blocked and prompted through the shell permission flow"
                        )
                    },
                    { TableCell("✓") },
                ),
                listOf(
                    { TableCell("3") },
                    {
                        TableCell(
                            ".pi/tool-manifest.json — checked-in list of known tools, intercepted tools, and custom tools"
                        )
                    },
                    { TableCell("") },
                ),
            ),
    )
}

/** Same skewed table, but with `minColumnWidth` set to ensure narrow columns stay visible. */
@Composable
private fun SkewedTableWithMinWidth() {
    val borderColor = JewelTheme.globalColors.borders.normal
    BasicTableLayout(
        rowCount = 4,
        columnCount = 3,
        cellBorderColor = borderColor,
        minColumnWidth = 48.dp,
        modifier = Modifier.fillMaxWidth(),
        rows =
            listOf(
                listOf(
                    { TableCell("Phase", fontWeight = FontWeight.Bold) },
                    { TableCell("What", fontWeight = FontWeight.Bold) },
                    { TableCell("Done", fontWeight = FontWeight.Bold) },
                ),
                listOf(
                    { TableCell("1") },
                    { TableCell("Intercept grep/find/ls — same denyRead + outside-project-root prompt as read") },
                    { TableCell("✓") },
                ),
                listOf(
                    { TableCell("2") },
                    {
                        TableCell(
                            "Deny-by-default catch-all — any unrecognized toolName gets blocked and prompted through the shell permission flow"
                        )
                    },
                    { TableCell("✓") },
                ),
                listOf(
                    { TableCell("3") },
                    {
                        TableCell(
                            ".pi/tool-manifest.json — checked-in list of known tools, intercepted tools, and custom tools"
                        )
                    },
                    { TableCell("") },
                ),
            ),
    )
}

/** A table where some columns are completely empty, verifying equal space distribution fallback. */
@Composable
private fun EmptyColumnsTable() {
    val borderColor = JewelTheme.globalColors.borders.normal
    BasicTableLayout(
        rowCount = 3,
        columnCount = 3,
        cellBorderColor = borderColor,
        modifier = Modifier.fillMaxWidth(),
        rows =
            listOf(
                listOf({ TableCell("") }, { TableCell("What", fontWeight = FontWeight.Bold) }, { TableCell("") }),
                listOf({ TableCell("") }, { TableCell("All content is here in the middle column") }, { TableCell("") }),
                listOf(
                    { TableCell("") },
                    { TableCell("Extra space is divided equally between the three columns") },
                    { TableCell("") },
                ),
            ),
    )
}

/** A table with many columns to verify proportional scale-down. */
@Composable
private fun ManyColumnsTable() {
    val borderColor = JewelTheme.globalColors.borders.normal
    BasicTableLayout(
        rowCount = 2,
        columnCount = 6,
        cellBorderColor = borderColor,
        modifier = Modifier.fillMaxWidth(),
        rows =
            listOf(
                (1..6).map { col ->
                    val label = "Col $col"
                    @Composable { TableCell(label, fontWeight = FontWeight.Bold) }
                },
                (1..6).map { col ->
                    val value = "Value $col"
                    @Composable { TableCell(value) }
                },
            ),
    )
}

@Composable
private fun TableCell(text: String, fontWeight: FontWeight = FontWeight.Normal) {
    Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontWeight = fontWeight)
}
