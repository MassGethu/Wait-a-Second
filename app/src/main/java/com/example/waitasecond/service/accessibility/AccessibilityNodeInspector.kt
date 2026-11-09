package com.example.waitasecond.service.accessibility

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

    fun inspect(root: AccessibilityNodeInfo, structuralOnly: Boolean = false): List<AccessibilityNodeSnapshot> {
        val instagram = root.packageName?.toString() == com.example.waitasecond.core.monitoring.AppPackages.INSTAGRAM
        val snapshots = mutableListOf<AccessibilityNodeSnapshot>()
        var visitedNodes = 0
        var complete = true
        fun <T> read(block: () -> T): T? = try { block() } catch (_: IllegalStateException) {
            complete = false
            null
        }

        fun visit(node: AccessibilityNodeInfo, depth: Int, ancestors: List<String>) {
            if (depth > MAX_DEPTH || visitedNodes >= MAX_NODES) { complete = false; return }
            visitedNodes++

            // Instagram context needs structural IDs and control labels only. Never read
            // message bodies, thread previews or account names from text nodes.
            val viewId = read { node.viewIdResourceName.cleanValue() }
            val text = if (structuralOnly || instagram || read { node.isEditable } == true) null else read { node.text.cleanValue() }
            val description = if (instagram) {
                val id = viewId.orEmpty()
                val privateHierarchy = (ancestors + id).any { it.contains("message", true) || it.contains("thread", true) || it.contains("inbox", true) || it.contains("conversation", true) }
                val control = listOf("_tab", "like_button", "comment_button", "share_button", "audio_button").any { id.endsWith(it, true) }
                if (!privateHierarchy && control) read { node.contentDescription.cleanValue() }?.takeIf {
                    it.lowercase() in setOf("reels","home","search","profile","like","unlike","comment","comments","share","original audio","use audio")
                } else null
            } else if (structuralOnly) null else read { node.contentDescription.cleanValue() }
            val className = read { node.className.cleanValue() }
            val childCount = read { node.childCount } ?: 0
            val isVisible = read { node.isVisibleToUser } == true
            val isSelected = read { node.isSelected } == true

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
                    isEditable = read { node.isEditable } == true,
                    isFocused = read { node.isFocused } == true
                )
            }

            val childrenToVisit = childCount.coerceAtMost(MAX_NODES - visitedNodes)
            if (childrenToVisit < childCount) complete = false
            for (index in 0 until childrenToVisit) {
                val child = try {
                    node.getChild(index)
                } catch (_: IllegalStateException) {
                    complete = false
                    null
                }
                if (child == null) complete = false
                if (child != null) try {
                    visit(child, depth + 1, ancestors + listOfNotNull(viewId))
                } finally {
                    @Suppress("DEPRECATION")
                    if (android.os.Build.VERSION.SDK_INT < 33) child.recycle()
                }
            }
        }

        visit(root, 0, emptyList())
        // Incomplete trees might omit DM evidence. Never classify a misleading partial tree.
        return if (complete) snapshots else emptyList()
    }

    private fun CharSequence?.cleanValue(): String? = this
        ?.toString()
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_VALUE_LENGTH)
        ?.takeIf { it.isNotEmpty() }

}
