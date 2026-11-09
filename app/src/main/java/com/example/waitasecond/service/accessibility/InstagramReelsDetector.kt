package com.example.waitasecond.service.accessibility

enum class InstagramScreen { HOME, DM_LIST, DM_CONVERSATION, SEARCH, PROFILE, REELS, OTHER, UNKNOWN }

data class InstagramScreenResult(
    val screenType: InstagramScreen,
    val confidence: Double,
    val matchedSignals: List<String>,
    val reason: String
) {
    val isReels: Boolean get() = screenType == InstagramScreen.REELS && confidence >= REELS_THRESHOLD
    companion object { const val REELS_THRESHOLD = 0.9 }
}

/** Conservative rules for the controlled demo. IDs are hypotheses to validate in device logs. */
object InstagramReelsDetector {
    fun detect(nodes: List<AccessibilityNodeSnapshot>): InstagramScreenResult {
        fun ids(vararg parts: String) = nodes.any { n -> parts.any { n.viewId.orEmpty().contains(it, true) } }
        fun label(vararg labels: String) = nodes.any { n ->
            listOfNotNull(n.text, n.contentDescription).any { value ->
                labels.any { value.equals(it, true) || Regex("^${Regex.escape(it)}(?:[ ,.:]|$)", RegexOption.IGNORE_CASE).containsMatchIn(value) }
            }
        }
        fun selected(label: String) = nodes.any { n -> n.isSelected &&
            listOfNotNull(n.text, n.contentDescription).any { Regex("^${Regex.escape(label)}(?:[ ,.:]|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) } }
        fun result(type: InstagramScreen, confidence: Double, vararg signals: String) =
            InstagramScreenResult(type, confidence, signals.toList(), signals.joinToString(" + "))

        val messageInput = nodes.any { n ->
            (n.isEditable || n.className.orEmpty().contains("EditText")) &&
                n.values().any { it.contains("message", true) || it.contains("chat", true) }
        } || ids("row_thread_composer", "message_input", "chat_input", "direct_text_input")
        val thread = ids("direct_thread_header", "direct_thread_content", "thread_view_root",
            "thread_message", "message_list", "conversation_header") ||
            nodes.any { it.viewId.orEmpty().substringAfterLast('/') == "direct_thread" }
        val send = label("Send", "Send message") || ids("send_button", "composer_send")
        // Chat evidence wins even if stale/selected Reels navigation remains in the tree.
        if (messageInput || thread) return result(InstagramScreen.DM_CONVERSATION,
            if (messageInput && (thread || send)) 0.98 else 0.92,
            if (messageInput) "Message composer" else "Conversation hierarchy", "DM evidence overrides Reels")
        if (ids("direct_inbox", "inbox_thread", "direct_threads", "inbox_list") ||
            (label("Messages", "Inbox") && (label("New message", "Primary", "Requests") || ids("inbox"))))
            return result(InstagramScreen.DM_LIST, 0.95, "Inbox and conversation list controls")

        val selectedReels = selected("Reels") || nodes.any { it.isSelected &&
            it.viewId.orEmpty().substringAfterLast('/').lowercase() in setOf("clips_tab", "reels_tab") }
        val viewer = ids("reel_viewer", "reels_viewer", "clips_viewer", "reel_feed", "clips_video", "clips_container", "clips_video_container")
        val like = label("Like", "Unlike") || ids("like_button")
        val comment = label("Comment", "Comments") || ids("comment_button")
        val share = label("Share", "Send reel") || ids("share_button")
        val audio = nodes.any { n -> listOfNotNull(n.text, n.contentDescription, n.viewId).any {
            it.contains("original audio", true) || it.contains("use audio", true) ||
                it.contains("clips_audio", true) || it.contains("reel_audio", true)
        } }
        val controls = listOf(like, comment, share).count { it }
        if (selected("Home") && !viewer) return result(InstagramScreen.HOME, 0.9, "Selected Home overrides navigation-only Reels evidence")
        if ((viewer && ((selectedReels && (audio || controls >= 1)) || controls >= 2)) ||
            (selectedReels && controls >= 2)) {
            val signals = mutableListOf<String>()
            if (viewer) signals += "Reels viewer ID"
            if (selectedReels) signals += "Selected Reels tab"
            if (audio) signals += "Reel audio control"
            if (controls > 0) signals += "$controls independent engagement controls"
            return InstagramScreenResult(InstagramScreen.REELS, if (viewer) 0.96 else 0.91,
                signals, signals.joinToString(" + "))
        }
        if (selected("Home") || ids("feed_timeline")) return result(InstagramScreen.HOME, 0.9, "Home/feed state")
        if (selected("Search") || ids("search_edit_text", "search_results")) return result(InstagramScreen.SEARCH, 0.9, "Search state")
        if (selected("Profile") || ids("profile_header", "profile_tab_layout")) return result(InstagramScreen.PROFILE, 0.9, "Profile state")
        return result(if (nodes.isEmpty()) InstagramScreen.UNKNOWN else InstagramScreen.OTHER,
            0.3, "Insufficient current-screen evidence; allowed")
    }
}
