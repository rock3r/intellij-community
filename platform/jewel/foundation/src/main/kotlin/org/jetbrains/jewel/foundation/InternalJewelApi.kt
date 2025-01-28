package org.jetbrains.jewel.foundation

import kotlin.RequiresOptIn.Level

/**
 * APIs marked with this annotation are considered internal to Jewel, and no guarantee is made
 * on their binary compatibility over time. You should not be using these APIs in code outside
 * Jewel/the IntelliJ Platform.
 */
@RequiresOptIn(
    level = Level.WARNING,
    message = "This is an internal API for Jewel and is subject to change without notice.",
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
public annotation class InternalJewelApi
