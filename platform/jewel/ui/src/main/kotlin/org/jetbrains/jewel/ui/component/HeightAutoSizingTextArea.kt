package org.jetbrains.jewel.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.styling.ScrollbarStyle
import org.jetbrains.jewel.ui.component.styling.TextAreaStyle
import org.jetbrains.jewel.ui.theme.scrollbarStyle
import org.jetbrains.jewel.ui.theme.textAreaStyle

/**
 * A [TextArea] variant that automatically adjusts its height based on the content, and supports vertical scrolling when
 * required (e.g., if its height is constrained and insufficient to fit all the content).
 *
 * @param state The state of the text field, which includes the current text and selection information.
 * @param style The style settings that define the appearance and metrics for the text area, such as padding, border,
 *   and colors.
 * @param modifier Optional [Modifier] for this text area.
 * @param placeholder A composable lambda to display when the current text is empty.
 * @see TextArea
 * @see DefaultTextAreaLayout
 */
@ExperimentalJewelApi
@Composable
public fun HeightAutoSizingTextArea(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    inputTransformation: InputTransformation? = null,
    textStyle: TextStyle = JewelTheme.defaultTextStyle,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.MultiLine(),
    onTextLayout: (Density.(getResult: () -> TextLayoutResult?) -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    style: TextAreaStyle = JewelTheme.textAreaStyle,
    outline: Outline = Outline.None,
    placeholder: @Composable (() -> Unit)? = null,
    outputTransformation: OutputTransformation? = null,
    undecorated: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
    scrollbarStyle: ScrollbarStyle? = JewelTheme.scrollbarStyle,
) {
    val textStyle = JewelTheme.defaultTextStyle

    InputField(
        state = state,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        inputTransformation = inputTransformation,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        lineLimits = lineLimits,
        onTextLayout = onTextLayout,
        interactionSource = interactionSource,
        outputTransformation = outputTransformation,
        scrollState = scrollState,
        decoratorProducer = {
            if (undecorated) {
                DefaultTextAreaDecorator(state, scrollState, style, scrollbarStyle, placeholder = placeholder)
            } else null
        },
        style = style,
        outline = outline,
    )
}
