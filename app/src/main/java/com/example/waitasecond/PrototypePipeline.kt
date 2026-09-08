package com.example.waitasecond

import android.content.Context
import android.content.Intent
import android.util.Log

object PrototypePipeline {
    const val SOURCE_YOUTUBE = "YouTube"
    const val SOURCE_INSTAGRAM = "Instagram"
    private val lastLogs = mutableMapOf<String, String>()
    private fun logChanged(tag: String, message: String) {
        if (lastLogs.put(tag, message) != message) Log.i(tag, message)
    }
    fun processYouTube(context: Context, nodes: List<AccessibilityNodeSnapshot>) {
        val screen = YouTubeClassifier.classify(nodes)
        logChanged("YouTubeClassifier", "Screen=${screen.screenType} title=${screen.title} channel=${screen.channel} candidates=${screen.candidates} reason=${screen.reason}")
        val result = if (screen.screenType == YouTubeScreen.VIDEO)
            DbmsRelevanceScorer.score(screen.title.orEmpty(), screen.channel)
            else RelevanceResult(0.5, RelevanceStatus.UNCERTAIN, screen.reason)
        if (screen.title != null) logChanged("Relevance", "title=${screen.title} score=${result.score} result=${result.status}")
        record(context, RuntimeObservation(SOURCE_YOUTUBE, screen.title ?: screen.channel?.let { "Video by $it (title unavailable)" } ?: screen.screenType.name,
            result, isVideo = screen.title != null, isKnown = screen.screenType != YouTubeScreen.OTHER, contentGroup = screen.channel))
    }
    fun processInstagram(context: Context, nodes: List<AccessibilityNodeSnapshot>) {
        val screen = InstagramReelsDetector.detect(nodes)
        logChanged("InstagramClassifier", "Screen=${screen.screenType} confidence=${screen.confidence} signals=${screen.matchedSignals} reason=${screen.reason}")
        val result = if (screen.isReels) RelevanceResult(0.0, RelevanceStatus.IRRELEVANT, screen.reason)
            else RelevanceResult(0.5, RelevanceStatus.UNCERTAIN, screen.reason)
        record(context, RuntimeObservation(SOURCE_INSTAGRAM, (if (screen.screenType == InstagramScreen.OTHER || screen.screenType == InstagramScreen.UNKNOWN) "Instagram — screen not recognized" else screen.screenType.name.replace('_', ' ')), result,
            isKnown = screen.screenType !in setOf(InstagramScreen.UNKNOWN, InstagramScreen.OTHER)))
    }
    private fun record(context: Context, observation: RuntimeObservation) {
        val token = SessionManager.observe(observation) ?: return
        val session = SessionManager.activeSession() ?: return
        val intent = Intent(context, InterventionActivity::class.java).apply {
            putExtra(InterventionActivity.EXTRA_GOAL, session.goal)
            putExtra(InterventionActivity.EXTRA_CONTENT, observation.content)
            putExtra(InterventionActivity.EXTRA_REASON, observation.result.reason)
            putExtra(InterventionActivity.EXTRA_CONTENT_KEY, token)
            putExtra(
                InterventionActivity.EXTRA_TARGET_PACKAGE,
                when (observation.sourceApp) {
                    SOURCE_INSTAGRAM -> SessionTracker.INSTAGRAM_PACKAGE
                    else -> SessionTracker.YOUTUBE_PACKAGE
                }
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        if (!SessionManager.interventionRequested(token)) return
        try {
            context.startActivity(intent)
        } catch (error: RuntimeException) {
            SessionManager.interventionLaunchFailed(token)
            Log.e("DistractionTracker", "Unable to launch intervention", error)
        }
    }
}
