package com.example.waitasecond

enum class YouTubeScreen { HOME, SEARCH, VIDEO, OTHER }
data class YouTubeScreenResult(
    val screenType: YouTubeScreen,
    val title: String?,
    val confidence: Double,
    val candidates: List<String>,
    val reason: String,
    val channel: String? = null
)

object YouTubeClassifier {
    private val controls = setOf("home", "shorts", "subscriptions", "library", "you", "like",
        "share", "comments", "settings", "play", "pause", "search", "subscribe", "dislike")
    fun classify(nodes: List<AccessibilityNodeSnapshot>): YouTubeScreenResult {
        fun id(n: AccessibilityNodeSnapshot) = n.viewId.orEmpty().substringAfterLast('/').lowercase()
        fun watchContext(n: AccessibilityNodeSnapshot) = n.ancestorIds.any { ancestor ->
            listOf("watch_metadata", "video_metadata", "watch_panel", "player_overlays", "video_title", "slim_video_information", "metadata").any { ancestor.contains(it, true) }
        }
        fun recommendation(n: AccessibilityNodeSnapshot) = n.ancestorIds.any { ancestor ->
            listOf("recommend", "related", "video_list", "search_results", "feed_item", "compact_video").any { ancestor.contains(it, true) }
        }
        val player = nodes.any { id(it) in setOf("player_view", "movie_player", "player_overlays", "watch_player", "player_control_play_pause_replay_button", "player_control_fullscreen_button", "player_control_seek_bar", "watch_while_player") } ||
            nodes.count { n -> !recommendation(n) && listOfNotNull(n.text, n.contentDescription).any {
                it.equals("Pause video", true) || it.equals("Play video", true) || it.equals("Enter full screen", true) ||
                    it.startsWith("Video player", true)
            } } >= 2
        val titleNodes = nodes.filter { n -> !recommendation(n) && (
            id(n) in setOf("watch_title", "watch_video_title", "video_title_text") ||
                (id(n) in setOf("title", "video_title", "title_text") && watchContext(n))) }
        val contextualTitles = nodes.filter { n -> !recommendation(n) &&
            n.contentDescription.orEmpty().contains("Expand", true) &&
            n.contentDescription.orEmpty().contains("title", true) && !n.text.isNullOrBlank() }
        val candidates = (titleNodes + contextualTitles).mapNotNull { n -> (n.text ?: n.contentDescription)?.trim() }
            .filter { it.length in 3..200 && it.lowercase() !in controls }.distinctBy { it.lowercase() }
        // Never rank candidates by DBMS/distraction keywords. Ambiguity must allow.
        val channels = nodes.filter { n -> !recommendation(n) && watchContext(n) &&
            id(n) in setOf("channel_name", "owner_name", "channel_title", "channel_title_text") }
            .mapNotNull { it.text ?: it.contentDescription }.toMutableList()
        // Observed on Nord CE4: the current owner is a merged accessibility node
        // such as "MrBeast 516m subscribers", without a resource ID.
        val ownerRow = Regex("^(.+?)\\s+[0-9][0-9.,]*\\s*[kmb]?\\s+subscribers$", RegexOption.IGNORE_CASE)
        nodes.filter { !recommendation(it) && watchContext(it) }.forEach { node ->
            ownerRow.matchEntire(node.contentDescription.orEmpty().trim())?.groupValues?.get(1)?.let(channels::add)
        }
        val channel = channels.distinct().singleOrNull()
        if (player && candidates.size == 1) return YouTubeScreenResult(YouTubeScreen.VIDEO,
            candidates.single(), 0.95, candidates, "Player plus current watch-title hierarchy", channel)
        val ownerControls = nodes.any { n -> watchContext(n) && !recommendation(n) &&
            listOfNotNull(n.text, n.contentDescription).any { it.equals("Join this channel", true) ||
                it.equals("Subscribe", true) || it.startsWith("Subscribe to ", true) ||
                it.startsWith("YouTube notifications are off", true) } }
        if (player && candidates.isEmpty() && channel != null && ownerControls)
            return YouTubeScreenResult(YouTubeScreen.VIDEO, null, 0.9, candidates,
                "Current player and channel-owner row; title unavailable", channel)
        val search = nodes.any { id(it) in setOf("search_edit_text", "search_query", "search_results") }
        val home = nodes.any { it.isSelected && (it.text ?: it.contentDescription).equals("Home", true) }
        return YouTubeScreenResult(when { search -> YouTubeScreen.SEARCH; home && !player -> YouTubeScreen.HOME; else -> YouTubeScreen.OTHER },
            null, 0.4, candidates, "No unambiguous current video title; allowed")
    }
}
