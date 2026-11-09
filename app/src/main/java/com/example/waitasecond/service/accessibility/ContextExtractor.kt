package com.example.waitasecond.service.accessibility

import com.example.waitasecond.core.monitoring.AppContext
import com.example.waitasecond.core.monitoring.AppSection
import com.example.waitasecond.core.monitoring.SupportedApp

/** Extraction is independent of session goals and intervention UI. */
object ContextExtractor {
    fun extract(app: SupportedApp, nodes: List<AccessibilityNodeSnapshot>): AppContext = when (app) {
        SupportedApp.YOUTUBE -> YouTubeClassifier.classify(nodes).let {
            AppContext(app, when (it.screenType) {
                YouTubeScreen.HOME -> AppSection.HOME
                YouTubeScreen.SEARCH -> AppSection.SEARCH
                YouTubeScreen.VIDEO -> AppSection.VIDEO
                YouTubeScreen.SHORTS -> AppSection.SHORTS
                YouTubeScreen.OTHER -> AppSection.UNKNOWN
            }, it.title, it.channel)
        }
        SupportedApp.INSTAGRAM -> InstagramReelsDetector.detect(nodes).let {
            AppContext(app, when (it.screenType) {
                InstagramScreen.DM_LIST, InstagramScreen.DM_CONVERSATION -> AppSection.DIRECT_MESSAGES
                InstagramScreen.HOME -> AppSection.FEED
                InstagramScreen.REELS -> if (it.isReels) AppSection.REELS else AppSection.UNKNOWN
                InstagramScreen.SEARCH -> AppSection.SEARCH
                InstagramScreen.PROFILE -> AppSection.PROFILE
                else -> AppSection.UNKNOWN
            }, isDirectConversation = it.screenType == InstagramScreen.DM_CONVERSATION)
        }
    }
}
