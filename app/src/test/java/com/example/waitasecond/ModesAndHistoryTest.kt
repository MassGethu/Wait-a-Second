package com.example.waitasecond

import com.example.waitasecond.core.distraction.*
import com.example.waitasecond.core.monitoring.*
import com.example.waitasecond.core.session.*
import com.example.waitasecond.data.model.*
import com.example.waitasecond.data.repository.StatisticsRepository
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ModesAndHistoryTest {
    private var now = 0L
    private val store = MemorySessionStore()
    private fun tracker(mode: FocusMode = FocusMode.LIGHT) = SessionTracker({ now }, wallClock = { now }).apply {
        attachStore(store); startSession("DBMS", 25, mode)
    }
    private fun launch(t: SessionTracker): String {
        t.foregroundChanged(AppPackages.YOUTUBE)
        val token = t.observe(RuntimeObservation("YouTube", "GTA Gameplay",
            RelevanceResult(0.0, RelevanceStatus.IRRELEVANT, "off_topic_video"), isVideo = true,
            context = AppContext(SupportedApp.YOUTUBE, AppSection.VIDEO)))!!
        assertTrue(t.interventionRequested(token)); assertTrue(t.interventionLaunched(token))
        return token
    }
    @Test fun bothModesBelongToPersistedSessionsAndEvents() {
        FocusMode.entries.forEach { mode ->
            val t = tracker(mode); launch(t)
            assertEquals(mode, t.activeSession()!!.mode)
            assertEquals(mode, store.sessions().last().session.mode)
            assertEquals(mode, t.storedEvents().last().mode)
            t.endSession()
        }
    }
    @Test fun lightGoBackHasNoResetAndRequiresNavigationConfirmation() {
        val t = tracker(); val token = launch(t)
        assertTrue(t.chooseIntervention(token, UserChoice.GO_BACK))
        assertEquals(InterventionPhase.RETURNING_TO_FOCUS, t.interventionState().phase)
        assertNull(t.interventionState().countdown)
        assertEquals(0, t.statistics().redirects)
        t.confirmReturnToFocus(); t.confirmReturnToFocus()
        assertEquals(EventOutcome.RETURNED_TO_FOCUS, t.storedEvents().single().outcome)
        assertEquals(1, t.statistics().redirects)
    }
    @Test fun lightContinueRequiresRetypeAndResolvesOnce() {
        val t = tracker(); val token = launch(t)
        assertTrue(t.chooseIntervention(token, UserChoice.CONTINUE_ANYWAY))
        assertEquals(InterventionPhase.LIGHT_RETYPE, t.interventionState().phase)
        assertNull(t.interventionState().countdown)
        assertFalse(t.chooseIntervention(token, UserChoice.CONTINUE_ANYWAY))
        assertTrue(t.completeRetype(token, t.retypeSentence(token).orEmpty())); assertFalse(t.completeRetype(token, t.retypeSentence(token).orEmpty()))
        assertFalse(t.isInterventionVisible())
        assertEquals(EventOutcome.CONTINUED_AFTER_RETYPE, t.storedEvents().single().outcome)
        assertEquals(0, t.statistics().redirects)
        assertEquals(1, t.storedEvents().size)
    }
    @Test fun retypingCannotBeCompletedByWrongInputOrAfterExpiry() {
        val t = tracker(); val token=launch(t)
        t.chooseIntervention(token,UserChoice.CONTINUE_ANYWAY)
        assertFalse(t.completeRetype(token,"incorrect"))
        assertEquals(EventOutcome.PENDING,t.storedEvents().single().outcome)
        now=25*60_000L
        assertFalse(t.completeRetype(token,"I am choosing this video over my goal: DBMS."))
        assertEquals(EventOutcome.INTERRUPTED,t.storedEvents().single().outcome)
    }
    @Test fun leavingRetypeReusesEventAndSuccessfulReturnToAppSuppressesEpisode() {
        val t=tracker(); val token=launch(t)
        t.chooseIntervention(token,UserChoice.CONTINUE_ANYWAY)
        t.foregroundChanged(AppPackages.OWN)
        t.cancelRetype(token)
        assertTrue(t.isCurrentIntervention(token)); assertEquals(1,t.storedEvents().size)
        t.foregroundChanged(AppPackages.YOUTUBE)
        t.foregroundChanged(AppPackages.OWN,interventionWindow=true) // Restored overlay, same event.
        t.chooseIntervention(token,UserChoice.CONTINUE_ANYWAY)
        assertTrue(t.completeRetype(token,t.retypeSentence(token)!!))
        now=60_000; t.foregroundChanged(AppPackages.YOUTUBE)
        assertNull(t.observe(RuntimeObservation("YouTube","GTA Gameplay",RelevanceResult(0.0,RelevanceStatus.IRRELEVANT,"off_topic_video"),isVideo=true,context=AppContext(SupportedApp.YOUTUBE,AppSection.VIDEO))))
        assertEquals(1,t.storedEvents().size)
    }
    @Test fun strictHasNoContinueEvenIfCallerTriesIt() {
        val t = tracker(FocusMode.STRICT); val token = launch(t)
        assertFalse(t.interventionState().canContinue)
        assertFalse(t.chooseIntervention(token, UserChoice.CONTINUE_ANYWAY))
        assertEquals(InterventionPhase.INTERVENTION, t.interventionState().phase)
        assertNull(t.interventionState().countdown)
        assertEquals(UserChoice.UNKNOWN, t.storedEvents().single().userChoice)
    }
    @Test fun strictGoBackWaitsForVisibleResetThenCountsCompletionOnce() {
        val t = tracker(FocusMode.STRICT); val token = launch(t)
        t.chooseIntervention(token, UserChoice.GO_BACK)
        assertEquals(InterventionPhase.STRICT_FOCUS_RESET, t.interventionState().phase)
        assertEquals(CountdownPurpose.STRICT_RESET, t.interventionState().countdown!!.purpose)
        now = 20_000; t.advanceIntervention(); t.confirmReturnToFocus()
        assertEquals(10_000L, t.countdownRemainingMillis())
        assertEquals(0, t.statistics().redirects)
        t.resetVisibility(token, true); now += 9_999; t.advanceIntervention()
        assertEquals(0, t.statistics().redirects)
        now++; t.advanceIntervention(); t.advanceIntervention()
        assertEquals(EventOutcome.RESET_COMPLETED, t.storedEvents().single().outcome)
        assertEquals(1, t.statistics().redirects)
        assertEquals(InterventionPhase.NONE, t.interventionState().phase)
    }
    @Test fun hidingAndRecreatingResetPreservesRemainingVisibleTime() {
        val t = tracker(FocusMode.STRICT); val token = launch(t)
        t.chooseIntervention(token, UserChoice.GO_BACK); t.resetVisibility(token, true)
        now = 4_000; t.resetVisibility(token, false)
        now = 100_000; t.advanceIntervention()
        assertEquals(6_000L, t.countdownRemainingMillis())
        t.resetVisibility(token, true); t.resetVisibility(token, true)
        now += 6_000; t.advanceIntervention()
        assertEquals(1, t.statistics().redirects)
    }
    @Test fun endingSessionCancelsEitherCountdownAndStoresActualDuration() {
        FocusMode.entries.forEach { mode ->
            now = 0
            val t = tracker(mode); val token = launch(t)
            if (mode == FocusMode.LIGHT) t.chooseIntervention(token, UserChoice.CONTINUE_ANYWAY)
            else { t.chooseIntervention(token, UserChoice.GO_BACK); t.resetVisibility(token, true) }
            now = 5_000; t.endSession(); now = 100_000; t.advanceIntervention()
            assertEquals(InterventionPhase.NONE, t.interventionState().phase)
            assertEquals(EventOutcome.INTERRUPTED, t.storedEvents().last().outcome)
            assertEquals(5_000L, t.statistics().studyMillis)
            assertEquals(5_000L, t.currentSession()!!.endedAt)
            assertEquals(0, t.statistics().redirects)
        }
    }
    @Test fun strictSessionCanAlwaysBeEndedDuringAnUnresolvedIntervention() {
        val t = tracker(FocusMode.STRICT); val token = launch(t)
        t.chooseIntervention(token, UserChoice.GO_BACK)
        assertEquals(InterventionPhase.STRICT_FOCUS_RESET, t.interventionState().phase)
        t.endSession(); assertFalse(t.isSessionActive())
    }
    @Test fun processRestorationKeepsModeButDoesNotRestoreStaleReset() {
        val t = tracker(FocusMode.STRICT); val token = launch(t)
        t.chooseIntervention(token, UserChoice.GO_BACK); t.resetVisibility(token, true)
        now = 4_000; t.checkpoint()
        val restored = SessionTracker({ now }, wallClock = { now }).apply { attachStore(store) }
        assertEquals(FocusMode.STRICT, restored.activeSession()!!.mode)
        assertEquals(InterventionPhase.NONE, restored.interventionState().phase)
        assertEquals(EventOutcome.INTERRUPTED, restored.storedEvents().single().outcome)
        assertEquals(0, restored.statistics().redirects)
    }
    @Test fun failedNavigationReturnsToInterventionWithoutStartingReset() {
        val t = tracker(FocusMode.STRICT); val token = launch(t)
        t.chooseIntervention(token, UserChoice.GO_BACK)
        t.interventionNavigationFailed(token)
        assertEquals(InterventionPhase.INTERVENTION, t.interventionState().phase)
        assertNull(t.interventionState().countdown)
        assertEquals(0, t.statistics().redirects)
        assertTrue(t.chooseIntervention(token, UserChoice.GO_BACK))
    }
    @Test fun leavingRetypeReturnsToSameIntervention() {
        val t = tracker(); val token = launch(t)
        t.chooseIntervention(token, UserChoice.CONTINUE_ANYWAY); now = 3_000
        t.cancelRetype(token)
        assertEquals(InterventionPhase.INTERVENTION, t.interventionState().phase)
        assertTrue(t.chooseIntervention(token, UserChoice.GO_BACK))
        assertNull(t.interventionState().countdown)
        t.confirmReturnToFocus(); now = 30_000; t.advanceIntervention()
        assertEquals(EventOutcome.RETURNED_TO_FOCUS, t.storedEvents().single().outcome)
    }
    @Test fun sessionExpiryWinsOverUnfinishedFriction() {
        val t = tracker(); t.startSession("DBMS", 1)
        now = 55_000; val token = launch(t); t.chooseIntervention(token, UserChoice.CONTINUE_ANYWAY)
        now = 70_000; t.advanceIntervention()
        assertEquals(SessionStatus.COMPLETED, t.currentSession()!!.status)
        assertEquals(60_000L, t.statistics().studyMillis)
        assertEquals(EventOutcome.INTERRUPTED, t.storedEvents().single().outcome)
    }
    @Test fun classifiersReturnIdenticalDecisionsForBothModesIncludingDm() {
        val classifier = RuleBasedDistractionClassifier()
        val contexts = listOf(AppContext(SupportedApp.INSTAGRAM, AppSection.DIRECT_MESSAGES),
            AppContext(SupportedApp.INSTAGRAM, AppSection.REELS), AppContext(SupportedApp.YOUTUBE, AppSection.SHORTS),
            AppContext(SupportedApp.YOUTUBE, AppSection.VIDEO, "DBMS joins"),
            AppContext(SupportedApp.YOUTUBE, AppSection.VIDEO, "GTA Gameplay"), AppContext(SupportedApp.YOUTUBE, AppSection.UNKNOWN))
        contexts.forEach { context ->
            val light = FocusSession("DBMS", 25, 0, mode = FocusMode.LIGHT)
            assertEquals(classifier.classify(light, context), classifier.classify(light.copy(mode = FocusMode.STRICT), context))
        }
    }
    private fun date(year: Int, month: Int, day: Int, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        clear(); set(year, month - 1, day, 0, 0, 0)
    }.timeInMillis
    @Test fun historyIncludesTodayAndSevenPreviousCalendarDaysWithZeroDays() {
        val zone = TimeZone.getTimeZone("Asia/Kolkata")
        val today = date(2026, 9, 10, zone)
        val oldest = date(2026, 9, 3, zone)
        val events = listOf(oldest, today - 1, today).mapIndexed { i, at ->
            DistractionEvent(timestamp = at, sessionId = "session", studyTopic = "DBMS", app = if (i == 0) SupportedApp.YOUTUBE else SupportedApp.INSTAGRAM,
                section = AppSection.REELS, category = "test", outcome = EventOutcome.RETURNED_TO_FOCUS)
        }
        val days = StatisticsRepository.calendarDays(emptyList(), events, today + 10_000, zone)
        assertEquals(8, days.size); assertEquals(oldest, days.first().start)
        assertEquals(1, days.first().statistics.attemptsByApp["YouTube"])
        assertEquals(1, days[6].statistics.attemptsByApp["Instagram"])
        assertEquals(1, days.last().statistics.attemptsByApp["Instagram"])
        assertEquals(0, days[1].statistics.attempts)
        assertEquals(2, StatisticsRepository.combine(days.takeLast(7)).attempts)
    }
    @Test fun daylightSavingUsesCalendarBoundariesRatherThanFixed24Hours() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val days = StatisticsRepository.calendarDays(emptyList(), emptyList(), date(2026, 3, 9, zone), zone)
        val spring = days.first { it.start == date(2026, 3, 8, zone) }
        assertEquals(23 * 60 * 60_000L, spring.end - spring.start)
        val autumn = StatisticsRepository.calendarDays(emptyList(), emptyList(), date(2026, 11, 2, zone), zone)
            .first { it.start == date(2026, 11, 1, zone) }
        assertEquals(25 * 60 * 60_000L, autumn.end - autumn.start)
    }
    @Test fun weeklyFocusClipsIntervalsAndCountsModesBySessionStart() {
        val zone = TimeZone.getTimeZone("UTC"); val today = date(2026, 9, 10, zone)
        val s = FocusSession("DBMS", 25, 0, status = SessionStatus.COMPLETED, startedAt = today - 20_000,
            accumulatedMillis = 30_000, mode = FocusMode.STRICT, endedAt = today + 20_000)
        val record = SessionRecord(s, listOf(TimeInterval(today - 20_000, today - 10_000), TimeInterval(today, today + 20_000)), emptyList(), 30_000, 1)
        val days = StatisticsRepository.calendarDays(listOf(record), emptyList(), today + 30_000, zone)
        assertEquals(10_000L, days[6].statistics.studyMillis)
        assertEquals(20_000L, days.last().statistics.studyMillis)
        assertEquals(1, days[6].sessionsByMode[FocusMode.STRICT])
        assertEquals(0, days.last().sessionsByMode[FocusMode.STRICT])
        assertEquals(30_000L, StatisticsRepository.combine(days).studyMillis)
    }
    @Test fun successfulRedirectDefinitionIsCentralAndModeSpecific() {
        val base = DistractionEvent(timestamp = 0, sessionId = "id", studyTopic = "DBMS", app = SupportedApp.YOUTUBE,
            section = AppSection.VIDEO, category = "test")
        assertTrue(StatisticsRepository.isSuccessfulRedirect(base.copy(outcome = EventOutcome.RETURNED_TO_FOCUS)))
        assertFalse(StatisticsRepository.isSuccessfulRedirect(base.copy(outcome = EventOutcome.CONTINUED_DISTRACTION)))
        assertFalse(StatisticsRepository.isSuccessfulRedirect(base.copy(mode = FocusMode.STRICT, outcome = EventOutcome.RETURNED_TO_FOCUS)))
        assertTrue(StatisticsRepository.isSuccessfulRedirect(base.copy(mode = FocusMode.STRICT, outcome = EventOutcome.RESET_COMPLETED)))
    }
    @Test fun returningToSupportedAppCannotBypassPendingStrictResetOrDuplicateAttempt() {
        val t = tracker(FocusMode.STRICT); val token = launch(t)
        t.chooseIntervention(token, UserChoice.GO_BACK)
        t.resetVisibility(token, true)
        now = 3_000; t.resetVisibility(token, false)
        assertTrue(t.remindStrictReset(token))
        assertFalse(t.interventionState().canContinue)
        assertEquals(1, t.storedEvents().size)
        now = 20_000
        t.chooseIntervention(token, UserChoice.GO_BACK)
        t.resetVisibility(token, true)
        assertEquals(7_000, t.countdownRemainingMillis())
        now = 27_000; t.advanceIntervention()
        assertEquals(EventOutcome.RESET_COMPLETED, t.storedEvents().single().outcome)
    }

    @Test fun unconfirmedLightNavigationCannotSuppressMonitoringForever() {
        val t = tracker(); val token = launch(t)
        t.chooseIntervention(token, UserChoice.GO_BACK)
        now = 15_001; t.advanceIntervention()
        assertEquals(InterventionPhase.NONE, t.interventionState().phase)
        assertEquals(EventOutcome.INTERRUPTED, t.storedEvents().single().outcome)
        t.confirmReturnToFocus()
        assertEquals(0, t.statistics().redirects)
    }

}
