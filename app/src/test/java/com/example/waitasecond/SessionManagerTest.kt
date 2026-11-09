package com.example.waitasecond

import com.example.waitasecond.core.distraction.DbmsRelevanceScorer
import com.example.waitasecond.core.session.SessionTracker
import com.example.waitasecond.data.model.RelevanceResult
import com.example.waitasecond.data.model.RelevanceStatus
import com.example.waitasecond.data.model.RuntimeObservation
import com.example.waitasecond.data.model.UserChoice

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionManagerTest {
    private var now = 0L
    private lateinit var tracker: SessionTracker
    @Before fun setup() {
        now = 0; tracker = SessionTracker({ now }, cooldownMillis = 0); tracker.startSession("Study DBMS", 90)
    }
    private fun video(title: String) = RuntimeObservation("YouTube", title, DbmsRelevanceScorer.score(title), isVideo = true)
    private fun instagram(screen: String, distracting: Boolean = false, known: Boolean = true) = RuntimeObservation("Instagram", screen,
        RelevanceResult(if (distracting) 0.0 else 0.5, if (distracting) RelevanceStatus.IRRELEVANT else RelevanceStatus.UNCERTAIN, "test"), isKnown = known)
    private fun youtube() = tracker.foregroundChanged(SessionTracker.YOUTUBE_PACKAGE)
    private fun instagram() = tracker.foregroundChanged(SessionTracker.INSTAGRAM_PACKAGE)
    private fun launch(next: RuntimeObservation): String {
        val token = tracker.observe(next)!!
        assertTrue(tracker.interventionLaunched(token))
        return token
    }
    @Test fun sameVideoNeverDuplicatesEvenAfterOldEightSecondWindow() {
        youtube(); tracker.observe(video("DBMS Normalization")); now = 90_000
        tracker.observe(video(" dbms normalization "))
        assertEquals(1, tracker.eventsSnapshot().size)
    }
    @Test fun titleChangeRecordsNewState() {
        youtube(); tracker.observe(video("DBMS")); tracker.observe(video("SQL Joins"))
        assertEquals(2, tracker.eventsSnapshot().size)
    }
    @Test fun repeatedPackageDoesNotResetTimer() {
        youtube(); now = 5_000; youtube(); now = 20_000; youtube()
        assertEquals(20_000L, tracker.appDurations()[SessionTracker.YOUTUBE_PACKAGE])
    }
    @Test fun transitionAndEndFlushExactlyOnce() {
        youtube(); now = 34_500; instagram(); now = 40_000; tracker.endSession()
        now = 80_000; tracker.endSession()
        assertEquals(34_500L, tracker.appDurations()[SessionTracker.YOUTUBE_PACKAGE])
        assertEquals(5_500L, tracker.appDurations()[SessionTracker.INSTAGRAM_PACKAGE])
    }
    @Test fun youtubeContentDurationsUseTransitions() {
        youtube(); tracker.observe(video("DBMS")); now = 10_000; tracker.observe(video("DBMS"))
        now = 20_000; tracker.observe(video("SQL")); now = 25_000; instagram()
        assertEquals(20_000L, tracker.youtubeDurations()["DBMS"])
        assertEquals(5_000L, tracker.youtubeDurations()["SQL"])
    }
    @Test fun sessionExpiryClampsAllDurationsToDeadline() {
        tracker.startSession("Study DBMS", 1); youtube(); tracker.observe(video("DBMS"))
        now = 100_000
        assertNull(tracker.activeSession()); assertEquals(0L, tracker.remainingMillis())
        assertEquals(60_000L, tracker.appDurations()[SessionTracker.YOUTUBE_PACKAGE])
        assertEquals(60_000L, tracker.youtubeDurations()["DBMS"])
    }
    @Test fun countdownDoesNotRequireTicks() {
        now = 123_456; assertEquals(5_276_544L, tracker.remainingMillis())
    }
    @Test fun reelsRepeatedAndContinueRemainOneAttemptIndefinitely() {
        instagram(); launch(instagram("REELS", true)); tracker.closeIntervention()
        repeat(30) { now += 10_000; assertNull(tracker.observe(instagram("REELS", true))) }
        assertEquals(1, tracker.distractionAttempts()); assertEquals(1, tracker.eventsSnapshot().size)
    }
    @Test fun reelsDmReelsCountsTwoAttempts() {
        instagram(); launch(instagram("REELS", true)); tracker.closeIntervention()
        tracker.observe(instagram("DM CONVERSATION")); launch(instagram("REELS", true))
        assertEquals(2, tracker.distractionAttempts())
    }
    @Test fun dmConversationSwitchIsOneScreenNoAttempts() {
        instagram(); repeat(10) { tracker.observe(instagram("DM CONVERSATION")) }
        assertEquals(0, tracker.distractionAttempts()); assertEquals(1, tracker.eventsSnapshot().size)
    }
    @Test fun unknownTreeDoesNotRearmReels() {
        instagram(); launch(instagram("REELS", true)); tracker.closeIntervention()
        tracker.observe(instagram("OTHER", known = false)); assertNull(tracker.observe(instagram("REELS", true)))
        assertEquals(1, tracker.distractionAttempts())
    }
    @Test fun genuineAppLeaveAndReturnRearmsAttempt() {
        instagram(); launch(instagram("REELS", true)); tracker.closeIntervention()
        tracker.foregroundChanged("launcher"); instagram(); launch(instagram("REELS", true))
        assertEquals(2, tracker.distractionAttempts())
    }
    @Test fun interventionWindowDoesNotRearmAndPausesContentTime() {
        youtube(); launch(video("GTA Gameplay")); now = 8_000
        tracker.foregroundChanged(SessionTracker.OWN_PACKAGE, true)
        now = 20_000; tracker.closeIntervention(); youtube()
        assertNull(tracker.observe(video("GTA Gameplay")))
        now = 25_000; tracker.endSession()
        assertEquals(1, tracker.distractionAttempts())
        assertEquals(13_000L, tracker.youtubeDurations()["GTA Gameplay"])
        assertEquals(12_000L, tracker.appDurations()[SessionTracker.OWN_PACKAGE])
    }
    @Test fun mainScreenInsteadOfOverlayReturnCountsAsRealLeave() {
        instagram(); launch(instagram("REELS", true))
        tracker.foregroundChanged(SessionTracker.OWN_PACKAGE, true); tracker.closeIntervention()
        tracker.foregroundChanged(SessionTracker.OWN_PACKAGE); instagram(); launch(instagram("REELS", true))
        assertEquals(2, tracker.distractionAttempts())
    }
    @Test fun changedDistractingVideoIsNewAttempt() {
        youtube(); launch(video("GTA Gameplay")); tracker.closeIntervention(); launch(video("Minecraft gameplay"))
        assertEquals(2, tracker.distractionAttempts())
    }
    @Test fun failedLaunchIsNotCountedOrMarkedBlocked() {
        youtube(); val token = tracker.observe(video("GTA"))!!; tracker.interventionLaunchFailed(token)
        assertEquals(0, tracker.distractionAttempts()); assertFalse(tracker.eventsSnapshot().single().blocked)
    }
    @Test fun duplicateLaunchAcknowledgementCannotCountTwice() {
        youtube(); val token = launch(video("GTA")); assertFalse(tracker.interventionLaunched(token))
        assertEquals(1, tracker.distractionAttempts())
    }
    @Test fun inactiveSessionRejectsObservations() {
        tracker.endSession(); youtube(); assertNull(tracker.observe(video("GTA"))); assertTrue(tracker.eventsSnapshot().isEmpty())
    }
    @Test fun newSessionResetsMetricsAndRejectsStaleToken() {
        youtube(); val token = tracker.observe(video("GTA"))!!
        tracker.startSession("Study DBMS", 90); assertFalse(tracker.interventionLaunched(token))
        assertEquals(0, tracker.distractionAttempts()); assertTrue(tracker.eventsSnapshot().isEmpty())
    }
    @Test fun requestedButInvisibleInterventionTimesOutWithoutCounting() {
        youtube(); val token = tracker.observe(video("GTA"))!!
        assertTrue(tracker.interventionRequested(token)); assertEquals(0, tracker.distractionAttempts())
        now = 5_001; assertFalse(tracker.isInterventionVisible())
        assertFalse(tracker.interventionLaunched(token)); assertEquals(0, tracker.distractionAttempts())
    }
    @Test fun realActivityAcknowledgementCountsRequestedLaunchOnce() {
        instagram(); val token = tracker.observe(instagram("REELS", true))!!
        tracker.interventionRequested(token); assertTrue(tracker.interventionLaunched(token))
        assertFalse(tracker.interventionLaunched(token)); assertEquals(1, tracker.distractionAttempts())
    }
    @Test fun staleActivityCannotCloseNewIntervention() {
        youtube(); val old = launch(video("GTA")); tracker.closeIntervention(old)
        val current = launch(video("Minecraft")); tracker.closeIntervention(old)
        assertTrue(tracker.isCurrentIntervention(current))
    }

    @Test fun returnToFocusKeepsSessionActive() {
        youtube()
        val token = launch(video("GTA Gameplay"))

        val leftIntervention = tracker.chooseIntervention(token, UserChoice.RETURN_TO_SESSION)

        assertTrue(leftIntervention)
        assertTrue(tracker.isSessionActive())
        assertEquals(1, tracker.distractionAttempts())
        assertFalse(tracker.isInterventionVisible())
    }

    @Test fun staleInterventionCannotAffectNewerSession() {
        youtube()
        val staleToken = launch(video("GTA Gameplay"))
        tracker.startSession("Study DBMS", 90)

        assertFalse(tracker.chooseIntervention(staleToken, UserChoice.RETURN_TO_SESSION))
        assertTrue(tracker.isSessionActive())
    }
    @Test fun titleCapitalizationDoesNotSplitContentTiming() {
        youtube(); tracker.observe(video("DBMS")); now = 5_000; tracker.observe(video(" dbms "))
        now = 10_000; tracker.endSession()
        assertEquals(mapOf("DBMS" to 10_000L), tracker.youtubeDurations())
    }
    @Test fun safeYouTubeScreenDoesNotPolluteContentDurationOrDrift() {
        youtube(); tracker.observe(video("DBMS")); now = 5_000
        tracker.observe(SessionTracker.allowed("YouTube", "HOME")); now = 15_000; tracker.endSession()
        assertEquals(5_000L, tracker.youtubeDurations()["DBMS"])
        assertEquals(listOf(0.9), tracker.drift().recentScores)
    }
    @Test fun channelArrivingAfterSameUnknownTitleCanTriggerOnce() {
        youtube()
        val title = "We Survived The Most Extreme Places On Earth"
        assertNull(tracker.observe(video(title)))
        val enriched = RuntimeObservation("YouTube", title, DbmsRelevanceScorer.score(title, "MrBeast"),
            isVideo = true, contentGroup = "MrBeast")
        launch(enriched); tracker.closeIntervention()
        assertNull(tracker.observe(enriched)); assertEquals(1, tracker.distractionAttempts())
    }
    @Test fun channelFallbackAndLaterTitleAreOneAttempt() {
        youtube()
        val fallback = RuntimeObservation("YouTube", "Video by MrBeast (title unavailable)",
            DbmsRelevanceScorer.score("", "MrBeast"), contentGroup = "MrBeast")
        launch(fallback); tracker.closeIntervention()
        val title = video("MrBeast challenge").copy(contentGroup = "MrBeast")
        assertNull(tracker.observe(title)); assertNull(tracker.observe(fallback))
        assertEquals(1, tracker.distractionAttempts())
    }
    @Test fun recentHistoryIsBounded() {
        youtube(); repeat(100) { tracker.observe(video("DBMS lesson $it")) }
        assertEquals(50, tracker.eventsSnapshot().size)
    }
}
