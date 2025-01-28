package org.jetbrains.jewel.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.styling.ScrollbarStyle
import org.jetbrains.jewel.ui.component.styling.TextAreaStyle

/**
 * Provides a default TextArea decorator with customizable style, scroll behavior, and optional placeholder content.
 *
 * @param scrollState The state of the scrolling logic for the text area's content. **MUST** be the same one used by the
 *   text area itself.
 * @param style Defines the visual and behavioral styling of the text area. **MUST** be the same one used by the text
 *   area itself.
 * @param scrollbarStyle Defines the appearance and behavior of the scrollbar, if set. If null, no scrollbar will
 *   display. **MUST** be the same one used by the text area itself.
 * @param modifier An optional modifier applied to the text area container.
 * @param placeholder Optional composable lambda to render a placeholder when the TextArea is empty.
 * @return A TextFieldDecorator that provides layout and decoration for the text area.
 */
public class DefaultTextAreaDecorator(
    private val state: TextFieldState,
    private val scrollState: ScrollState,
    private val style: TextAreaStyle,
    private val scrollbarStyle: ScrollbarStyle?,
    private val modifier: Modifier = Modifier.Companion,
    private val placeholder: @Composable (() -> Unit)? = null,
) : TextFieldDecorator {

    @Composable
    override fun Decoration(innerTextField: @Composable (() -> Unit)) {
        val contentPadding = computeTextAreaContentPadding(scrollbarStyle, style)

        val minSize = style.metrics.minSize
        DefaultTextAreaLayout(
            textArea = {
                val content: @Composable () -> Unit = {
                    Box(Modifier.Companion.padding(contentPadding).border(1.dp, Color.Companion.Magenta)) {
                        innerTextField()
                    }
                }

                val canScroll by remember {
                    derivedStateOf { scrollState.canScrollBackward || scrollState.canScrollForward }
                }
                // Note: due to how BTF works, for now canScroll is always true, because the BTF's scroll state has
                // its maxValue always set to Int.MAX_VALUE, regardless of whether that's actually the case.
                // This logic future proofs us for when this will be fixed.
                if (scrollbarStyle != null && canScroll) {
                    TextAreaScrollableContainer(
                        scrollState = scrollState,
                        style = scrollbarStyle,
                        modifier = Modifier.Companion.padding(style.metrics.borderWidth),
                        content = content,
                    )
                } else {
                    Box(Modifier.Companion.padding(style.metrics.borderWidth)) { content() }
                }
            },
            modifier = modifier.defaultMinSize(minWidth = minSize.width, minHeight = minSize.height),
            placeholder = {
                Placeholder(
                    shouldShow = state.text.isEmpty(),
                    textColor = style.colors.placeholder,
                    modifier =
                        Modifier.Companion.padding(style.metrics.contentPadding).padding(style.metrics.borderWidth),
                    content = placeholder,
                )
            },
        )
    }
}
