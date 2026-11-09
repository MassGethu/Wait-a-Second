package com.example.waitasecond.core.monitoring

import com.example.waitasecond.core.distraction.DbmsRelevanceScorer
import com.example.waitasecond.core.session.FocusSession
import com.example.waitasecond.data.model.RelevanceStatus

enum class AppSection { HOME, VIDEO, SHORTS, SEARCH, FEED, REELS, EXPLORE, DIRECT_MESSAGES, PROFILE, UNKNOWN }
data class AppContext(
    val app: SupportedApp,
    val section: AppSection,
    val visibleTitle: String? = null,
    val channel: String? = null,
    val isDirectConversation: Boolean = false
)
enum class ClassificationDecision { ALLOW, DISTRACTING, UNKNOWN }
data class ClassificationResult(val decision: ClassificationDecision, val category: String)
fun interface DistractionClassifier {
    fun classify(session: FocusSession, context: AppContext): ClassificationResult
}
class RuleBasedDistractionClassifier : DistractionClassifier {
    override fun classify(session: FocusSession, context: AppContext): ClassificationResult {
        if (!session.isActive || context.section == AppSection.UNKNOWN)
            return ClassificationResult(ClassificationDecision.UNKNOWN, "unknown_context")
        if (context.section in setOf(AppSection.REELS, AppSection.SHORTS))
            return ClassificationResult(ClassificationDecision.DISTRACTING, "short_form_video")
        if (context.section != AppSection.VIDEO)
            return ClassificationResult(ClassificationDecision.ALLOW, "supported_safe_section")
        // Retain the validated demo vocabulary only for DBMS-related intentions.
        if (DbmsRelevanceScorer.score(session.goal).status != RelevanceStatus.RELEVANT)
            return ClassificationResult(ClassificationDecision.UNKNOWN, "topic_rules_unavailable")
        return when (DbmsRelevanceScorer.score(context.visibleTitle.orEmpty(), context.channel).status) {
            RelevanceStatus.IRRELEVANT -> ClassificationResult(ClassificationDecision.DISTRACTING, "off_topic_video")
            RelevanceStatus.RELEVANT -> ClassificationResult(ClassificationDecision.ALLOW, "topic_match")
            RelevanceStatus.UNCERTAIN -> ClassificationResult(ClassificationDecision.UNKNOWN, "uncertain_relevance")
        }
    }
}
