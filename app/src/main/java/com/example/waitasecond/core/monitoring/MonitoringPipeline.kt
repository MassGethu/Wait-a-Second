package com.example.waitasecond.core.monitoring

import com.example.waitasecond.core.session.SessionTracker
import com.example.waitasecond.data.model.RelevanceResult
import com.example.waitasecond.data.model.RelevanceStatus
import com.example.waitasecond.data.model.RuntimeObservation

/** Coordinates extracted context, a replaceable classifier, and intervention. No Android UI code. */
class MonitoringPipeline(
    private val sessions: SessionTracker,
    private val overlay: OverlayController,
    private val classifier: DistractionClassifier = RuleBasedDistractionClassifier(),
    private val log: (String, String) -> Unit = { _, _ -> }
) {
    fun process(context: AppContext) {
        val session = sessions.activeSession() ?: return
        sessions.observeContext(context)
        val result = classifier.classify(session, context)
        val observation = RuntimeObservation(context.app.displayName,
            context.visibleTitle ?: context.channel?.let { "Video by $it (title unavailable)" } ?: context.section.name,
            RelevanceResult(when (result.decision) {
                ClassificationDecision.ALLOW -> 0.9
                ClassificationDecision.DISTRACTING -> 0.0
                ClassificationDecision.UNKNOWN -> 0.5
            }, when (result.decision) {
                ClassificationDecision.ALLOW -> RelevanceStatus.RELEVANT
                ClassificationDecision.DISTRACTING -> RelevanceStatus.IRRELEVANT
                ClassificationDecision.UNKNOWN -> RelevanceStatus.UNCERTAIN
            }, result.category), isVideo = context.visibleTitle != null,
            isKnown = result.decision != ClassificationDecision.UNKNOWN, contentGroup = context.channel, context = context)
        val token = sessions.observe(observation) ?: return
        log("CLASSIFIER", "${context.app}/${context.section}: ${result.decision}")
        overlay.showOverlay(OverlayData(token, session.goal, context, sessions.remainingMillis()))
    }
}
