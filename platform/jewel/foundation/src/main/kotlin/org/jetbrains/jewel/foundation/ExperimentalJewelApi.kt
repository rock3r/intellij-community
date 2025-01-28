package org.jetbrains.jewel.foundation

import kotlin.RequiresOptIn.Level

/**
 * APIs marked with this annotation are considered experimental, and no guarantee is made on their binary compatibility
 * over time. They might change in incompatible ways, get renamed, or even removed.
 *
 * You should not rely on experimental APIs unless you're willing to take that risk.
 */
@RequiresOptIn(
    level = Level.WARNING,
    message = "This is an experimental API for Jewel and is likely to change before becoming stable.",
)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.VALUE_PARAMETER,
)
public annotation class ExperimentalJewelApi
