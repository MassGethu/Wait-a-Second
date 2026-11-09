package com.example.waitasecond

import com.example.waitasecond.core.monitoring.AppContext
import com.example.waitasecond.core.monitoring.AppPackages
import com.example.waitasecond.core.monitoring.AppSection
import com.example.waitasecond.core.monitoring.ClassificationDecision
import com.example.waitasecond.service.accessibility.ContextExtractor
import com.example.waitasecond.core.monitoring.RuleBasedDistractionClassifier
import com.example.waitasecond.core.monitoring.SupportedApp
import com.example.waitasecond.core.session.FocusSession
import com.example.waitasecond.core.session.SessionStatus
import com.example.waitasecond.core.session.SessionTracker
import com.example.waitasecond.data.model.DistractionEvent
import com.example.waitasecond.data.model.MemorySessionStore
import com.example.waitasecond.data.model.RelevanceResult
import com.example.waitasecond.data.model.RelevanceStatus
import com.example.waitasecond.data.model.RuntimeObservation
import com.example.waitasecond.data.model.SessionStore
import com.example.waitasecond.data.model.UserChoice
import com.example.waitasecond.data.repository.StatisticsRepository
import com.example.waitasecond.service.accessibility.AccessibilityNodeSnapshot

import com.example.waitasecond.core.monitoring.MonitoringPipeline
import com.example.waitasecond.core.monitoring.OverlayController
import com.example.waitasecond.core.monitoring.OverlayData
import org.junit.Assert.*
import org.junit.Test

class FoundationTest {
    private var now = 0L
    private var epoch = 1_000_000L
    private fun tracker(store: SessionStore = MemorySessionStore(), boot: Int = 1) =
        SessionTracker({ now }, wallClock = { epoch + now }, bootCount = { boot }).apply { attachStore(store) }
    private fun reels() = RuntimeObservation("Instagram", "REELS",
        RelevanceResult(0.0, RelevanceStatus.IRRELEVANT, "short_form_video"),
        context = AppContext(SupportedApp.INSTAGRAM, AppSection.REELS))
    private fun launch(t: SessionTracker): String {
        t.foregroundChanged(AppPackages.INSTAGRAM)
        val token = t.observe(reels())!!
        assertTrue(t.interventionRequested(token)); assertTrue(t.interventionLaunched(token))
        return token
    }
    @Test fun activeTimerExpiresWithoutUiTicks() {
        val t = tracker(); t.startSession("French", 1)
        now = 30_000; assertEquals(30_000L, t.remainingMillis())
        now = 500_000
        assertEquals(SessionStatus.COMPLETED, t.currentSession()!!.status)
        assertEquals(60_000L, t.statistics().studyMillis)
        assertEquals(60_000L, t.dailyStatistics(0, Long.MAX_VALUE).studyMillis)
    }
    @Test fun activeCancellationIsIdempotent() {
        val t = tracker(); t.startSession("DBMS", 1)
        now = 10_000; t.endSession(); t.endSession()
        assertEquals(SessionStatus.CANCELLED, t.currentSession()!!.status)
        assertEquals(10_000L, t.statistics().studyMillis)
        assertEquals(10_000L, t.dailyStatistics(0, Long.MAX_VALUE).studyMillis)
    }
    @Test fun processRecoveryCountsDowntimeOnceButNeverInventsAppExposure() {
        val store = MemorySessionStore(); val t = tracker(store)
        val id = t.startSession("DBMS", 1).id
        t.foregroundChanged(AppPackages.YOUTUBE)
        now = 10_000; t.checkpoint()
        now = 25_000; val restored = tracker(store)
        assertEquals(id, restored.activeSession()!!.id)
        assertEquals(35_000L, restored.remainingMillis())
        assertEquals(25_000L, restored.dailyStatistics(0, Long.MAX_VALUE).studyMillis)
        assertEquals(10_000L, restored.dailyStatistics(0, Long.MAX_VALUE).screenMillis)
        restored.checkpoint(); now = 30_000
        val twice = tracker(store)
        assertEquals(30_000L, twice.remainingMillis())
        assertEquals(30_000L, twice.dailyStatistics(0, Long.MAX_VALUE).studyMillis)
    }
    @Test fun rebootContinuesFromLastCheckpointWithNewMonotonicOrigin() {
        val store = MemorySessionStore(); val t = tracker(store)
        t.startSession("DBMS", 1); now = 10_000; t.checkpoint()
        now = 1_000; val recovered = tracker(store, boot = 2)
        assertEquals(SessionStatus.ACTIVE, recovered.currentSession()!!.status)
        assertEquals(50_000L, recovered.remainingMillis())
    }
    @Test fun recoveryPastDeadlineCompletesAndClampsHistory() {
        val store = MemorySessionStore(); val t = tracker(store)
        t.startSession("DBMS", 1); now = 10_000; t.checkpoint()
        now = 90_000; val recovered = tracker(store)
        assertEquals(SessionStatus.COMPLETED, recovered.currentSession()!!.status)
        assertEquals(60_000L, recovered.dailyStatistics(0, Long.MAX_VALUE).studyMillis)
    }
    @Test fun dailyTotalsClipCrossMidnightIntervals() {
        epoch = 0
        val t = tracker(); t.startSession("DBMS", 2)
        now = 110_000; t.endSession()
        assertEquals(60_000L, t.dailyStatistics(50_000, 150_000).studyMillis)
        assertEquals(50_000L, t.dailyStatistics(0, 50_000).studyMillis)
    }
    @Test fun cooldownSuppressesRapidLeaveReturnAndStaleTokens() {
        val t = tracker(); t.startSession("DBMS", 25)
        val token = launch(t); t.chooseIntervention(token, UserChoice.CONTINUE_ANYWAY)
        t.completeRetype(token, t.retypeSentence(token).orEmpty())
        now = 11_000; t.observe(SessionTracker.allowed("Instagram", "DIRECT_MESSAGES"))
        assertNull(t.observe(reels()))
        now = 20_000; t.observe(SessionTracker.allowed("Instagram", "DIRECT_MESSAGES"))
        val second = launch(t)
        assertFalse(t.chooseIntervention(token, UserChoice.RETURN_TO_SESSION))
        assertTrue(t.isCurrentIntervention(second)); assertEquals(2, t.storedEvents().size)
    }
    @Test fun eventOutcomesPersistAndReturnRequiresConfirmation() {
        val store = MemorySessionStore(); val t = tracker(store); t.startSession("DBMS", 25)
        val token = launch(t)
        now = 60_000 // Time spent deciding is not the redirect-confirmation deadline.
        t.chooseIntervention(token, UserChoice.RETURN_TO_SESSION)
        assertEquals(0, t.dailyStatistics(0, Long.MAX_VALUE).redirects)
        t.confirmReturnToFocus(); t.confirmReturnToFocus()
        assertEquals(1, t.dailyStatistics(0, Long.MAX_VALUE).redirects)
        assertEquals(UserChoice.RETURN_TO_SESSION, tracker(store).storedEvents().single().userChoice)
    }
    @Test fun processDeathDismissesUnresolvedOverlayAndRetainsAttempt() {
        val store = MemorySessionStore(); val t = tracker(store); t.startSession("DBMS", 25); launch(t)
        val restored = tracker(store)
        assertFalse(restored.isInterventionVisible())
        assertEquals(UserChoice.AUTO_DISMISSED, restored.storedEvents().single().userChoice)
        assertEquals(1, restored.statistics().attempts)
    }
    @Test fun normalizedInboxAndConversationsAreTheSameContext() {
        fun node(id: String) = AccessibilityNodeSnapshot(null, null, id, false)
        val inbox = ContextExtractor.extract(SupportedApp.INSTAGRAM, listOf(node("direct_inbox")))
        val chatA = ContextExtractor.extract(SupportedApp.INSTAGRAM, listOf(node("direct_thread")))
        val chatB = ContextExtractor.extract(SupportedApp.INSTAGRAM, listOf(node("conversation_header")))
        assertEquals(AppSection.DIRECT_MESSAGES, inbox.section)
        assertEquals(inbox.section, chatA.section); assertFalse(inbox.isDirectConversation); assertTrue(chatA.isDirectConversation); assertEquals(chatA, chatB)
    }
    @Test fun shortsRequiresSpecificViewerAndControls() {
        fun node(id: String) = AccessibilityNodeSnapshot(null, null, id, false)
        assertEquals(AppSection.UNKNOWN, ContextExtractor.extract(SupportedApp.YOUTUBE,
            listOf(node("shorts_player"))).section)
        assertEquals(AppSection.SHORTS, ContextExtractor.extract(SupportedApp.YOUTUBE,
            listOf(node("shorts_player"), node("reel_like_button"))).section)
    }
    @Test fun classifierScopesDbmsRulesAndAllowsMessagingAndUnknown() {
        val classifier = RuleBasedDistractionClassifier()
        val session = FocusSession("French", 25, 0)
        fun classify(section: AppSection) = classifier.classify(session, AppContext(SupportedApp.INSTAGRAM, section)).decision
        assertEquals(ClassificationDecision.ALLOW, classify(AppSection.DIRECT_MESSAGES))
        assertEquals(ClassificationDecision.UNKNOWN, classify(AppSection.UNKNOWN))
        assertEquals(ClassificationDecision.DISTRACTING, classify(AppSection.REELS))
        val video = AppContext(SupportedApp.YOUTUBE, AppSection.VIDEO, "GTA Gameplay")
        assertEquals(ClassificationDecision.UNKNOWN, classifier.classify(session, video).decision)
        assertEquals(ClassificationDecision.DISTRACTING, classifier.classify(session.copy(goal = "DBMS"), video).decision)
        assertEquals(ClassificationDecision.UNKNOWN, classifier.classify(session.copy(status = SessionStatus.CANCELLED), video).decision)
    }
    @Test fun perAppStatisticsComeFromEventsAcrossSessions() {
        val session = FocusSession("DBMS", 1, 0)
        val events = listOf(SupportedApp.YOUTUBE, SupportedApp.INSTAGRAM, SupportedApp.INSTAGRAM).mapIndexed { i, app ->
            DistractionEvent(timestamp = i.toLong(), sessionId = session.id, studyTopic = session.goal,
                app = app, section = AppSection.UNKNOWN, category = "test", returnedToFocus = i == 0)
        }
        val stats = StatisticsRepository.aggregate(emptyList(), events, 0, 2)
        assertEquals(2, stats.attempts); assertEquals(1, stats.redirects)
        assertEquals(1, stats.attemptsByApp["YouTube"]); assertEquals(1, stats.attemptsByApp["Instagram"])
    }
    @Test fun pipelineDoesNotRearmWhenVideoMetadataTemporarilyDisappears() {
        val t = tracker(); t.startSession("Study DBMS", 25)
        t.foregroundChanged(AppPackages.YOUTUBE)
        var shows = 0
        val overlay = object : OverlayController {
            override fun showOverlay(data: OverlayData) {
                shows++
                t.interventionRequested(data.token); t.interventionLaunched(data.token)
                t.chooseIntervention(data.token, UserChoice.CONTINUE_ANYWAY)
                now += 10_000; t.advanceIntervention()
            }
            override fun dismissOverlay() {}
        }
        val pipeline = MonitoringPipeline(t, overlay)
        val video = AppContext(SupportedApp.YOUTUBE, AppSection.VIDEO, "A challenge", "MrBeast")
        pipeline.process(video)
        now = 20_000
        pipeline.process(video.copy(channel = null))
        pipeline.process(video)
        assertEquals(1, shows)
    }
    @Test fun maximumLengthUiTopicCanStartWithDisplayPrefix() {
        assertTrue(tracker().startSession("Study " + "a".repeat(80), 1).isActive)
    }
}
