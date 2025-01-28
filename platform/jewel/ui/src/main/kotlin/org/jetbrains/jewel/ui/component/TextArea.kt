package org.jetbrains.jewel.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.theme.LocalContentColor
import org.jetbrains.jewel.foundation.theme.LocalTextStyle
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.styling.ScrollbarStyle
import org.jetbrains.jewel.ui.component.styling.ScrollbarVisibility.AlwaysVisible
import org.jetbrains.jewel.ui.component.styling.TextAreaStyle
import org.jetbrains.jewel.ui.theme.scrollbarStyle
import org.jetbrains.jewel.ui.theme.textAreaStyle
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs

@Composable
public fun TextArea(
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
    decorationBoxModifier: Modifier = Modifier,
    outputTransformation: OutputTransformation? = null,
    undecorated: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
    scrollbarStyle: ScrollbarStyle? = JewelTheme.scrollbarStyle,
) {
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
        style = style,
        outline = outline,
        outputTransformation = outputTransformation,
        decoratorProducer = { fieldState ->
            TextFieldDecorator {
                if (undecorated) {
                    NoTextAreaDecorator(scrollState, style, scrollbarStyle)
                } else {
                    DefaultTextAreaDecorator(
                        state = state,
                        scrollState = scrollState,
                        modifier = decorationBoxModifier,
                        style = style,
                        scrollbarStyle = scrollbarStyle,
                        placeholder = placeholder,
                    )
                }
            }
        },
        scrollState = scrollState,
    )
}

/**
 * Creates a decorator for a text area that draws no decoration for the area itself. Optionally wraps the text input
 * with a scrollable container if a scrollbar style is provided.
 *
 * This decorator is useful to seamlessly incorporate a text area in a larger layout.
 *
 * @param scrollState The state of the scrolling logic for the text area's content. **MUST** be the same one used by the
 *   text area itself.
 * @param style Defines the visual and behavioral styling of the text area. **MUST** be the same one used by the text
 *   area itself.
 * @param scrollbarStyle Defines the appearance and behavior of the scrollbar, if used. If null, no scrollbar will
 *   display. **MUST** be the same one used by the text area itself.
 * @param modifier An optional modifier applied to the text area container.
 * @return A [TextFieldDecorator] containing the decorated composable.
 */
@Composable
public fun NoTextAreaDecorator(
    scrollState: ScrollState,
    style: TextAreaStyle,
    scrollbarStyle: ScrollbarStyle?,
    modifier: Modifier = Modifier,
): TextFieldDecorator = TextFieldDecorator { innerTextField ->
    val contentPadding = computeTextAreaContentPadding(scrollbarStyle, style)

    if (scrollbarStyle != null) {
        TextAreaScrollableContainer(
            scrollState = scrollState,
            style = scrollbarStyle,
            modifier = Modifier,
            content = { Box(Modifier.padding(contentPadding)) { innerTextField() } },
        )
    } else {
        Box(modifier.padding(contentPadding)) { innerTextField() }
    }
}

@Composable
internal fun computeTextAreaContentPadding(scrollbarStyle: ScrollbarStyle?, style: TextAreaStyle): PaddingValues =
    if (scrollbarStyle == null) {
        style.metrics.contentPadding
    } else {
        val scrollbarPadding = scrollbarContentSafePadding(scrollbarStyle)
        val padding = style.metrics.contentPadding
        val layoutDirection = LocalLayoutDirection.current
        PaddingValues(
            start = padding.calculateStartPadding(layoutDirection),
            top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(layoutDirection) + scrollbarPadding,
            bottom = padding.calculateBottomPadding(),
        )
    }

/**
 * A composable function that creates a scrollable container with a vertical scrollbar to use in [TextArea] decorators.
 * This container can only be scrolled vertically.
 *
 * @param scrollState The state of the scroll logic.
 * @param style The style of the scrollbar.
 * @param modifier Optional modifier applied to the component.
 * @param content The composable content to be displayed inside the scrollable area.
 */
@Composable
public fun TextAreaScrollableContainer(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    style: ScrollbarStyle = JewelTheme.scrollbarStyle,
    content: @Composable () -> Unit,
) {
    var keepVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    VerticalScrollingLayout(
        scrollbar = {
            VerticalScrollbar(
                scrollState,
                style = style,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Default).padding(1.dp),
                keepVisible = keepVisible,
            )
        },
        modifier = modifier.withKeepVisible(style.scrollbarVisibility.lingerDuration, scope) { keepVisible = it },
        scrollbarStyle = style,
    ) {
        Box(Modifier.layoutId(ID_CONTENT)) { content() }
    }
}

@Composable
private fun VerticalScrollingLayout(
    scrollbar: (@Composable () -> Unit)?,
    modifier: Modifier,
    scrollbarStyle: ScrollbarStyle,
    content: @Composable () -> Unit,
) {
    Layout(
        content = {
            content()

            if (scrollbar != null) {
                Box(Modifier.layoutId(ID_SCROLLBAR)) { scrollbar() }
            }
        },
        modifier,
    ) { measurables, incomingConstraints ->
        val isMacOs = hostOs == OS.MacOS
        val contentMeasurable = measurables.find { it.layoutId == ID_CONTENT } ?: error("Content not provided")

        val isAlwaysVisible = scrollbarStyle.scrollbarVisibility is AlwaysVisible
        val scrollbarMeasurable =
            measurables.find { it.layoutId == ID_SCROLLBAR } ?: error("The vertical scrollbar is missing")

        val scrollbarWidth =
            if (!isMacOs || isAlwaysVisible) {
                // The scrollbar on Windows/Linux, and on macOS when AlwaysVisible, needs
                // to be accounted for by subtracting its width from the available width)
                scrollbarMeasurable.minIntrinsicWidth(incomingConstraints.maxHeight)
            } else {
                0
            }

        val contentWidth = incomingConstraints.maxWidth - scrollbarWidth
        val contentConstraints =
            Constraints(
                minWidth = contentWidth,
                maxWidth = contentWidth,
                minHeight = incomingConstraints.minHeight,
                maxHeight = incomingConstraints.maxHeight,
            )
        val contentPlaceable = contentMeasurable.measure(contentConstraints)

        val width = contentPlaceable.width + scrollbarWidth
        val height = contentPlaceable.height

        val verticalScrollbarConstraints = Constraints.fixedHeight(height)
        val verticalScrollbarPlaceable = scrollbarMeasurable.measure(verticalScrollbarConstraints)

        layout(width, height) {
            contentPlaceable.placeRelative(x = 0, y = 0, zIndex = 0f)
            verticalScrollbarPlaceable.placeRelative(x = width - verticalScrollbarPlaceable.width, y = 0, zIndex = 1f)
        }
    }
}

private const val ID_CONTENT = "ScrollingContainer_content"
private const val ID_SCROLLBAR = "ScrollingContainer_scrollbar"

@Composable
internal fun Placeholder(
    shouldShow: Boolean,
    textColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    if (!shouldShow || content == null) return
    Box(modifier.clipToBounds()) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(color = textColor),
            LocalContentColor provides textColor,
            content = content,
        )
    }
}

@Deprecated("Please use TextArea(state) instead. If you want to observe text changes, use snapshotFlow { state.text }")
@Composable
public fun TextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    outline: Outline = Outline.None,
    placeholder: @Composable (() -> Unit)? = null,
    undecorated: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions(),
    maxLines: Int = Int.MAX_VALUE,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextAreaStyle = JewelTheme.textAreaStyle,
    textStyle: TextStyle = JewelTheme.defaultTextStyle,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    decorationBoxModifier: Modifier = Modifier,
) {
    var textFieldValueState by remember { mutableStateOf(TextFieldValue(text = value)) }
    val textFieldValue = textFieldValueState.copy(text = value)
    var lastTextValue by remember(value) { mutableStateOf(value) }

    @Suppress("DEPRECATION")
    TextArea(
        value = textFieldValue,
        onValueChange = { newTextFieldValueState ->
            textFieldValueState = newTextFieldValueState

            val stringChangedSinceLastInvocation = lastTextValue != newTextFieldValueState.text
            lastTextValue = newTextFieldValueState.text

            if (stringChangedSinceLastInvocation) {
                onValueChange(newTextFieldValueState.text)
            }
        },
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        outline = outline,
        placeholder = placeholder,
        undecorated = undecorated,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        maxLines = maxLines,
        onTextLayout = onTextLayout,
        style = style,
        textStyle = textStyle,
        interactionSource = interactionSource,
        decorationBoxModifier = decorationBoxModifier,
    )
}

@Deprecated("Please use TextArea(state) instead. If you want to observe text changes, use snapshotFlow { state.text }")
@Composable
public fun TextArea(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    placeholder: @Composable (() -> Unit)? = null,
    undecorated: Boolean = false,
    outline: Outline = Outline.None,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions(),
    maxLines: Int = Int.MAX_VALUE,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextAreaStyle = JewelTheme.textAreaStyle,
    textStyle: TextStyle = JewelTheme.defaultTextStyle,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    decorationBoxModifier: Modifier = Modifier,
) {
    val minSize = style.metrics.minSize
    val contentPadding = style.metrics.contentPadding

    @Suppress("DEPRECATION")
    InputField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.defaultMinSize(minWidth = minSize.width, minHeight = minSize.height),
        enabled = enabled,
        readOnly = readOnly,
        outline = outline,
        undecorated = undecorated,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = false,
        maxLines = maxLines,
        onTextLayout = onTextLayout,
        style = style,
        textStyle = textStyle,
        interactionSource = interactionSource,
    ) { innerTextField, _ ->
        DefaultTextAreaLayout(
            textArea = innerTextField,
            modifier = decorationBoxModifier,
            placeholder = {
                Placeholder(
                    shouldShow = value.text.isEmpty(),
                    textColor = style.colors.placeholder,
                    modifier = Modifier.padding(contentPadding).padding(style.metrics.borderWidth),
                    content = placeholder,
                )
            },
        )
    }
}

/**
 * Default layout logic for a TextArea component. Handles the layout of the text area, including its placeholder and
 * inner text field.
 *
 * @param textArea A composable lambda that renders the actual base text area.
 * @param modifier Modifier applied to the decoration box.
 * @param placeholder A composable lambda for rendering the placeholder content, or null if no placeholder is required.
 */
@ExperimentalJewelApi
@Composable
public fun DefaultTextAreaLayout(
    textArea: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: (@Composable () -> Unit)? = null,
) {
    Layout(
        content = {
            if (placeholder != null) {
                Box(
                    modifier = Modifier.layoutId(PLACEHOLDER_ID),
                    contentAlignment = Alignment.TopStart,
                    propagateMinConstraints = true,
                ) {
                    placeholder()
                }
            }

            Box(
                modifier = Modifier.layoutId(TEXT_AREA_ID),
                contentAlignment = Alignment.TopStart,
                propagateMinConstraints = true,
            ) {
                textArea()
            }
        },
        modifier,
    ) { measurables, incomingConstraints ->
        val textAreaPlaceable = measurables.single { it.layoutId == TEXT_AREA_ID }.measure(incomingConstraints)

        // Measure placeholder
        val placeholderConstraints = Constraints.fixed(textAreaPlaceable.width, textAreaPlaceable.height)
        val placeholderPlaceable = measurables.find { it.layoutId == PLACEHOLDER_ID }?.measure(placeholderConstraints)

        val width = textAreaPlaceable.width.coerceAtLeast(incomingConstraints.minWidth)
        val height = textAreaPlaceable.height.coerceAtLeast(incomingConstraints.minHeight)

        layout(width, height) {
            // Placed similar to the input text below
            placeholderPlaceable?.placeRelative(0, 0)

            // Placed top-start
            textAreaPlaceable.placeRelative(0, 0)
        }
    }
}

private const val PLACEHOLDER_ID = "Placeholder"
private const val TEXT_AREA_ID = "TextField"
