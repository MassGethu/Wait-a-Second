package com.example.waitasecond

import org.junit.Assert.*
import org.junit.Test

class SessionStatisticsTest {
    private var now = 0L
    private fun tracker() = SessionTracker({ now }).apply { startSession("Study DBMS", 25) }
    private fun reels() = RuntimeObservation("Instagram", "REELS",
        RelevanceResult(0.0, RelevanceStatus.IRRELEVANT, "Viewer"))
    private fun attempt(t: SessionTracker): String {
        t.foregroundChanged(SessionTracker.INSTAGRAM_PACKAGE)
        val token = t.observe(reels())!!
        t.interventionLaunched(token)
        return token
    }
    @Test fun emptyStatisticsDoNotInventData() {
        val stats = SessionTracker({ now }).statistics()
        assertFalse(stats.hasSession)
        assertEquals(0, stats.attempts)
        assertEquals(0L, stats.studyMillis)
        assertEquals(0, stats.percentage("Instagram"))
    }
    @Test fun timerAndMeasuredAppTotalsRemainAfterEnding() {
        val t = tracker()
        now = 10_000; t.foregroundChanged(SessionTracker.YOUTUBE_PACKAGE)
        now = 40_000; t.foregroundChanged(SessionTracker.INSTAGRAM_PACKAGE)
        now = 50_000; t.endSession()
        now = 80_000
        val stats = t.statistics()
        assertEquals(50_000L, stats.studyMillis)
        assertEquals(40_000L, stats.screenMillis)
        assertEquals(30_000L, stats.monitoredAppMillis["YouTube"])
    }
    @Test fun continueCountsAttemptButNotRedirect() {
        val t = tracker(); val token = attempt(t); t.closeIntervention(token)
        val stats = t.statistics()
        assertEquals(1, stats.attempts)
        assertEquals(100, stats.percentage("Instagram"))
        assertEquals(0, stats.redirects)
    }
    @Test fun returnCountsOnlyAfterTargetHomeIsConfirmedOnce() {
        val t = tracker(); val token = attempt(t)
        assertTrue(t.leaveInterventionForAppHome(token))
        assertEquals(0, t.statistics().redirects)
        t.observe(reels())
        assertEquals(0, t.statistics().redirects)
        repeat(3) { t.observe(SessionTracker.allowed("Instagram", "HOME")) }
        assertEquals(1, t.statistics().redirects)
        assertTrue(t.isSessionActive())
        assertEquals(1, t.statistics().attempts)
    }
    @Test fun failedOrStaleReturnDoesNotIncreaseRedirects() {
        val t = tracker(); attempt(t)
        assertFalse(t.leaveInterventionForAppHome("stale"))
        assertEquals(0, t.statistics().redirects)
    }
    @Test fun unrelatedHomeAndLateHomeDoNotCountRedirects() {
        val t = tracker(); val token = attempt(t); t.leaveInterventionForAppHome(token)
        t.observe(SessionTracker.allowed("YouTube", "HOME"))
        assertEquals(0, t.statistics().redirects)
        now = 16_000; t.observe(SessionTracker.allowed("Instagram", "HOME"))
        assertEquals(0, t.statistics().redirects)
    }
    @Test fun nextSessionResetsAllSessionMetrics() {
        val t = tracker(); val token = attempt(t); t.leaveInterventionForAppHome(token)
        t.observe(SessionTracker.allowed("Instagram", "HOME"))
        t.startSession("Study SQL joins", 25)
        assertEquals("Study SQL joins", t.activeSession()!!.goal)
        assertEquals(0, t.statistics().attempts)
        assertEquals(0, t.statistics().redirects)
        assertEquals(0L, t.statistics().screenMillis)
    }
    @Test fun supportedTopicEditingDoesNotPretendToSupportOtherSubjects() {
        assertTrue(SessionTracker.isSupportedTopic("DBMS"))
        assertTrue(SessionTracker.isSupportedTopic("SQL joins"))
        assertTrue(SessionTracker.isSupportedTopic("Normalization"))
        assertFalse(SessionTracker.isSupportedTopic(""))
        assertFalse(SessionTracker.isSupportedTopic("French"))
    }
    @Test fun distributionIsIndependentOfBoundedRecentHistory() {
        val t = tracker()
        repeat(60) {
            val token = attempt(t); t.closeIntervention(token)
            t.observe(SessionTracker.allowed("Instagram", "HOME"))
        }
        assertEquals(60, t.statistics().attemptsByApp["Instagram"])
        assertEquals(100, t.statistics().percentage("Instagram"))
    }
}
