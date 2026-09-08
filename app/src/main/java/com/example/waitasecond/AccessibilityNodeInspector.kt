package com.example.waitasecond

import android.view.accessibility.AccessibilityNodeInfo

data class AccessibilityNodeSnapshot(
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val isSelected: Boolean,
    val depth: Int = 0,
    val className: String? = null,
    val childCount: Int = 0,
    val ancestorIds: List<String> = emptyList(),
    val isEditable: Boolean = false,
    val isFocused: Boolean = false
) {
    fun values(): List<String> = listOfNotNull(text, contentDescription, viewId)
}

object AccessibilityNodeInspector {
    private const val MAX_DEPTH = 60
    private const val MAX_NODES = 1500
    private const val MAX_VALUE_LENGTH = 200

    fun inspect(root: AccessibilityNodeInfo): List<AccessibilityNodeSnapshot> {
        val snapshots = mutableListOf<AccessibilityNodeSnapshot>()
        var visitedNodes = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int, ancestors: List<String>) {
            if (depth > MAX_DEPTH || visitedNodes >= MAX_NODES) return
            visitedNodes++

            val text = tryRead { node.text.cleanValue() }
            val description = tryRead { node.contentDescription.cleanValue() }
            val viewId = tryRead { node.viewIdResourceName.cleanValue() }
            val className = tryRead { node.className.cleanValue() }
            val childCount = tryRead { node.childCount } ?: 0
            val isVisible = tryRead { node.isVisibleToUser } == true
            val isSelected = tryRead { node.isSelected } == true

            // Only collect values exposed as visible, but still traverse the descendants.
            if (isVisible && (text != null || description != null || viewId != null)) {
                snapshots += AccessibilityNodeSnapshot(
                    text = text,
                    contentDescription = description,
                    viewId = viewId,
                    isSelected = isSelected,
                    depth = depth,
                    className = className,
                    childCount = childCount,
                    ancestorIds = ancestors,
                    isEditable = tryRead { node.isEditable } == true,
                    isFocused = tryRead { node.isFocused } == true
                )
            }

            val childrenToVisit = childCount.coerceAtMost(MAX_NODES - visitedNodes)
            for (index in 0 until childrenToVisit) {
                val child = try {
                    node.getChild(index)
                } catch (_: IllegalStateException) {
                    null
                }
                if (child != null) visit(child, depth + 1, ancestors + listOfNotNull(viewId))
            }
        }

        visit(root, 0, emptyList())
        // Remote trees update during traversal. Keep valid siblings when a child disappears.
        return snapshots
    }

    private fun CharSequence?.cleanValue(): String? = this
        ?.toString()
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_VALUE_LENGTH)
        ?.takeIf { it.isNotEmpty() }

    private inline fun <T> tryRead(block: () -> T): T? = try {
        block()
    } catch (_: IllegalStateException) {
        // Nodes can become stale while the remote app updates its hierarchy.
        null
    }
}
