package org.jetbrains.jewel.ui.component

import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.intellij.platform.icons.Icon as IconDescriptor
import com.intellij.platform.icons.modifiers.IconModifier
import com.intellij.platform.icons.scale.IconScale
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.styling.IconButtonStyle
import org.jetbrains.jewel.ui.component.styling.TooltipStyle
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icon.stroke
import org.jetbrains.jewel.ui.icon.withModifier
import org.jetbrains.jewel.ui.painter.PainterHint
import org.jetbrains.jewel.ui.painter.hints.Stroke
import org.jetbrains.jewel.ui.theme.iconButtonStyle
import org.jetbrains.jewel.ui.theme.tooltipStyle

/**
 * Renders a toggleable icon action button using the given [key] to resolve the icon.
 *
 * The button visually reflects its toggled [value] and notifies [onValueChange] when the user clicks it. An optional
 * [extraHint] may be supplied to further customise how the icon is painted.
 */
@Composable
public fun ToggleableIconActionButton(
    key: IconKey,
    contentDescription: String?,
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusable: Boolean = true,
    style: IconButtonStyle = JewelTheme.iconButtonStyle,
    colorFilter: ColorFilter? = null,
    extraHint: PainterHint? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    iconClass: Class<*> = key.iconClass,
) {
    BaseToggleableIconActionButton(
        key = key,
        contentDescription = contentDescription,
        iconClass = iconClass,
        value = value,
        enabled = enabled,
        focusable = focusable,
        style = style,
        interactionSource = interactionSource,
        colorFilter = colorFilter,
        extraHint = extraHint,
        onValueChange = onValueChange,
        modifier = modifier,
        iconModifier = iconModifier,
    )
}

/**
 * Renders a toggleable icon action button using the given [key] to resolve the icon, with a tooltip shown on hover.
 *
 * The button visually reflects its toggled [value] and notifies [onValueChange] when the user clicks it. The [tooltip]
 * composable is displayed according to [tooltipPlacement]. An optional [extraHint] may be supplied to further customise
 * how the icon is painted.
 */
@Suppress("ComposableParamOrder") // To fix in JEWEL-932
@Composable
public fun ToggleableIconActionButton(
    key: IconKey,
    contentDescription: String?,
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusable: Boolean = true,
    style: IconButtonStyle = JewelTheme.iconButtonStyle,
    colorFilter: ColorFilter? = null,
    extraHint: PainterHint? = null,
    tooltipStyle: TooltipStyle = JewelTheme.tooltipStyle,
    tooltipModifier: Modifier = Modifier,
    tooltipPlacement: TooltipPlacement = FixedCursorPoint(offset = DpOffset(0.dp, 16.dp)),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    iconClass: Class<*> = key.iconClass,
    tooltip: @Composable () -> Unit,
) {
    Tooltip(tooltip, style = tooltipStyle, modifier = tooltipModifier, tooltipPlacement = tooltipPlacement) {
        @Suppress("ModifierNotUsedAtRoot") // This is intentional
        BaseToggleableIconActionButton(
            key = key,
            contentDescription = contentDescription,
            iconClass = iconClass,
            value = value,
            enabled = enabled,
            focusable = focusable,
            style = style,
            interactionSource = interactionSource,
            colorFilter = colorFilter,
            extraHint = extraHint,
            onValueChange = onValueChange,
            modifier = modifier,
            iconModifier = iconModifier,
        )
    }
}

/**
 * Renders a toggleable icon action button using the given [key] to resolve the icon.
 *
 * The button visually reflects its toggled [value] and notifies [onValueChange] when the user clicks it. The
 * [extraHints] array is forwarded to the icon painter to further customise rendering.
 */
@Composable
public fun ToggleableIconActionButton(
    key: IconKey,
    contentDescription: String?,
    value: Boolean,
    extraHints: Array<PainterHint>,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusable: Boolean = true,
    style: IconButtonStyle = JewelTheme.iconButtonStyle,
    colorFilter: ColorFilter? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    iconClass: Class<*> = key.iconClass,
) {
    CoreToggleableIconActionButton(
        key = key,
        contentDescription = contentDescription,
        iconClass = iconClass,
        value = value,
        enabled = enabled,
        focusable = focusable,
        style = style,
        interactionSource = interactionSource,
        extraHints = extraHints,
        onValueChange = onValueChange,
        modifier = modifier,
        iconModifier = iconModifier,
        colorFilter = colorFilter,
    )
}

/**
 * Renders a toggleable icon action button using the given [key] to resolve the icon, with a tooltip shown on hover.
 *
 * The button visually reflects its toggled [value] and notifies [onValueChange] when the user clicks it. The
 * [extraHints] array is forwarded to the icon painter to further customise rendering. The [tooltip] composable is
 * displayed according to [tooltipPlacement].
 */
@Suppress("ComposableParamOrder") // To fix in JEWEL-932
@Composable
public fun ToggleableIconActionButton(
    key: IconKey,
    contentDescription: String?,
    value: Boolean,
    extraHints: Array<PainterHint>,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusable: Boolean = true,
    style: IconButtonStyle = JewelTheme.iconButtonStyle,
    colorFilter: ColorFilter? = null,
    tooltipStyle: TooltipStyle = JewelTheme.tooltipStyle,
    tooltipModifier: Modifier = Modifier,
    tooltipPlacement: TooltipPlacement = FixedCursorPoint(offset = DpOffset(0.dp, 16.dp)),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    iconClass: Class<*> = key.iconClass,
    tooltip: @Composable () -> Unit,
) {
    Tooltip(tooltip, style = tooltipStyle, modifier = tooltipModifier, tooltipPlacement = tooltipPlacement) {
        @Suppress("ModifierNotUsedAtRoot") // This is intentional
        CoreToggleableIconActionButton(
            key = key,
            contentDescription = contentDescription,
            iconClass = iconClass,
            value = value,
            enabled = enabled,
            focusable = focusable,
            style = style,
            interactionSource = interactionSource,
            extraHints = extraHints,
            onValueChange = onValueChange,
            modifier = modifier,
            iconModifier = iconModifier,
            colorFilter = colorFilter,
        )
    }
}

/**
 * Renders a toggleable icon action button showing the icon described by [icon].
 *
 * The button visually reflects its toggled [value] and notifies [onValueChange] when the user clicks it. Icon
 * descriptors carry their own rendering configuration, so unlike the [IconKey] overloads this one takes no
 * [PainterHint]s; the toggled-on stroke recolouring the [IconKey] overloads apply is still applied here, through the
 * descriptor's own [stroke] modifier, so both families tint identically.
 */
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun ToggleableIconActionButton(
    icon: IconDescriptor,
    contentDescription: String?,
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusable: Boolean = true,
    style: IconButtonStyle = JewelTheme.iconButtonStyle,
    scale: IconScale? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    CoreToggleableIconActionButton(
        icon = icon,
        contentDescription = contentDescription,
        value = value,
        enabled = enabled,
        focusable = focusable,
        style = style,
        scale = scale,
        interactionSource = interactionSource,
        onValueChange = onValueChange,
        modifier = modifier,
        iconModifier = iconModifier,
    )
}

/**
 * Renders a toggleable icon action button showing the icon described by [icon], with a tooltip shown on hover.
 *
 * The button visually reflects its toggled [value] and notifies [onValueChange] when the user clicks it. The [tooltip]
 * composable is displayed according to [tooltipPlacement].
 */
@Suppress("ComposableParamOrder") // To fix in JEWEL-932
@ApiStatus.Experimental
@ExperimentalJewelApi
@Composable
public fun ToggleableIconActionButton(
    icon: IconDescriptor,
    contentDescription: String?,
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusable: Boolean = true,
    style: IconButtonStyle = JewelTheme.iconButtonStyle,
    scale: IconScale? = null,
    tooltipStyle: TooltipStyle = JewelTheme.tooltipStyle,
    tooltipModifier: Modifier = Modifier,
    tooltipPlacement: TooltipPlacement = FixedCursorPoint(offset = DpOffset(0.dp, 16.dp)),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    tooltip: @Composable () -> Unit,
) {
    Tooltip(tooltip, style = tooltipStyle, modifier = tooltipModifier, tooltipPlacement = tooltipPlacement) {
        @Suppress("ModifierNotUsedAtRoot") // This is intentional
        CoreToggleableIconActionButton(
            icon = icon,
            contentDescription = contentDescription,
            value = value,
            enabled = enabled,
            focusable = focusable,
            style = style,
            scale = scale,
            interactionSource = interactionSource,
            onValueChange = onValueChange,
            modifier = modifier,
            iconModifier = iconModifier,
        )
    }
}

@Composable
private fun CoreToggleableIconActionButton(
    icon: IconDescriptor,
    contentDescription: String?,
    value: Boolean,
    enabled: Boolean,
    focusable: Boolean,
    style: IconButtonStyle,
    scale: IconScale?,
    interactionSource: MutableInteractionSource,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    ToggleableIconButton(value, onValueChange, modifier, enabled, focusable, style, interactionSource) {
        val strokeColor by style.colors.toggleableForegroundFor(it)
        // Mirrors the IconKey overloads: an unspecified foreground means "leave the icon alone", and
        // withModifier hands the descriptor straight back, so the unstyled path stays allocation-free.
        val strokedIcon =
            remember(icon, strokeColor) {
                if (strokeColor.isSpecified) icon.withModifier(IconModifier.stroke(strokeColor)) else icon
            }
        Icon(icon = strokedIcon, contentDescription = contentDescription, modifier = iconModifier, scale = scale)
    }
}

@Composable
private fun BaseToggleableIconActionButton(
    key: IconKey,
    contentDescription: String?,
    iconClass: Class<*>,
    value: Boolean,
    enabled: Boolean,
    focusable: Boolean,
    style: IconButtonStyle,
    interactionSource: MutableInteractionSource,
    colorFilter: ColorFilter?,
    extraHint: PainterHint?,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
) {
    if (extraHint != null) {
        CoreToggleableIconActionButton(
            key = key,
            contentDescription = contentDescription,
            iconClass = iconClass,
            value = value,
            enabled = enabled,
            focusable = focusable,
            style = style,
            interactionSource = interactionSource,
            extraHint = extraHint,
            onValueChange = onValueChange,
            modifier = modifier,
            iconModifier = iconModifier,
            colorFilter = colorFilter,
        )
    } else {
        CoreToggleableIconActionButton(
            key = key,
            contentDescription = contentDescription,
            iconClass = iconClass,
            value = value,
            enabled = enabled,
            focusable = focusable,
            style = style,
            interactionSource = interactionSource,
            extraHints = emptyArray(),
            onValueChange = onValueChange,
            modifier = modifier,
            iconModifier = iconModifier,
            colorFilter = colorFilter,
        )
    }
}

@Composable
private fun CoreToggleableIconActionButton(
    key: IconKey,
    contentDescription: String?,
    iconClass: Class<*>,
    value: Boolean,
    enabled: Boolean,
    focusable: Boolean,
    style: IconButtonStyle,
    interactionSource: MutableInteractionSource,
    extraHints: Array<PainterHint>,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
) {
    ToggleableIconButton(value, onValueChange, modifier, enabled, focusable, style, interactionSource) {
        val strokeColor by style.colors.toggleableForegroundFor(it)
        Icon(
            key = key,
            contentDescription = contentDescription,
            iconClass = iconClass,
            modifier = iconModifier,
            hints = arrayOf(Stroke(strokeColor), *extraHints),
            colorFilter = colorFilter,
        )
    }
}

@Composable
private fun CoreToggleableIconActionButton(
    key: IconKey,
    contentDescription: String?,
    iconClass: Class<*>,
    value: Boolean,
    enabled: Boolean,
    focusable: Boolean,
    style: IconButtonStyle,
    interactionSource: MutableInteractionSource,
    extraHint: PainterHint,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
) {
    ToggleableIconButton(value, onValueChange, modifier, enabled, focusable, style, interactionSource) {
        val strokeColor by style.colors.toggleableForegroundFor(it)
        Icon(
            key = key,
            contentDescription = contentDescription,
            iconClass = iconClass,
            modifier = iconModifier,
            hints = arrayOf(Stroke(strokeColor), extraHint),
            colorFilter = colorFilter,
        )
    }
}
