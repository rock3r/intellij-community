package org.jetbrains.jewel.foundation.actionSystem

import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.traverseAncestors
import org.jetbrains.jewel.foundation.shortcut.ShortcutResolverRootNode

internal class DataProviderElement(val dataProvider: DataProviderContext.() -> Unit) :
    ModifierNodeElement<DataProviderNode>() {
    override fun create(): DataProviderNode = DataProviderNode(dataProvider)

    override fun update(node: DataProviderNode) {
        node.dataProvider = dataProvider
        if (node.hasFocus) {
            node.traverseAncestors(ShortcutResolverRootNode.TraverseKey) { ancestor ->
                val root = ancestor as? ShortcutResolverRootNode ?: return@traverseAncestors true
                if (!root.state.reactivePresentation) root.state.presentations.invalidate()
                false
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DataProviderElement

        return dataProvider == other.dataProvider
    }

    override fun hashCode(): Int = dataProvider.hashCode()
}
