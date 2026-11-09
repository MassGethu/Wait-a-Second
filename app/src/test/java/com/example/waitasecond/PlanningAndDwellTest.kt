package com.example.waitasecond

import com.example.waitasecond.core.distraction.DmDwellPolicy
import com.example.waitasecond.core.distraction.InterventionPhase
import com.example.waitasecond.core.monitoring.*
import com.example.waitasecond.core.planning.*
import com.example.waitasecond.core.session.*
import com.example.waitasecond.data.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PlanningAndDwellTest {
    @Test fun thresholdSnoozeAndDepartureGrace() {
        val dwell = DmDwellPolicy(100, 30, 100)
        dwell.observe(true,0); assertFalse(dwell.shouldPrompt(99)); assertTrue(dwell.shouldPrompt(100))
        dwell.shown(100); assertFalse(dwell.shouldPrompt(1000))
        dwell.snooze(1000); assertFalse(dwell.shouldPrompt(1099)); assertTrue(dwell.shouldPrompt(1100))
        dwell.observe(false,1100); assertFalse(dwell.shouldPrompt(1101))
        dwell.observe(true,1129); assertTrue(dwell.shouldPrompt(1129))
        dwell.observe(false,1130); dwell.observe(true,1160)
        assertFalse(dwell.shouldPrompt(1259)); assertTrue(dwell.shouldPrompt(1260))
    }
    @Test fun cooldownSurvivesInboxAndLongerAppDeparture() {
        val dwell = DmDwellPolicy(5, 30, 600)
        dwell.observe(true, 0); assertTrue(dwell.shouldPrompt(5)); dwell.shown(5)
        dwell.observe(false, 6); dwell.observe(true, 40)
        assertFalse(dwell.shouldPrompt(604)); assertTrue(dwell.shouldPrompt(605))
    }
    @Test fun inboxDoesNotCountAndChatSwitchDoesNotRestartVisit() {
        var now=0L
        val tracker=SessionTracker({now},wallClock={now})
        tracker.startSession("DBMS",60); tracker.foregroundChanged(AppPackages.INSTAGRAM)
        tracker.observeContext(AppContext(SupportedApp.INSTAGRAM,AppSection.DIRECT_MESSAGES))
        now=600_000; assertNull(tracker.pollDmCheckin())
        val chat=AppContext(SupportedApp.INSTAGRAM,AppSection.DIRECT_MESSAGES,isDirectConversation=true)
        tracker.observeContext(chat); now+=300_000; tracker.observeContext(chat)
        now+=300_000; assertNotNull(tracker.pollDmCheckin())
    }
    @Test fun personalChoiceLeavesMessagingOpenAndDoesNotSuppressReels() {
        var now = 0L
        val tracker = SessionTracker({ now }, wallClock = { now })
        tracker.startSession("DBMS", 60, FocusMode.STRICT)
        tracker.foregroundChanged(AppPackages.INSTAGRAM)
        tracker.observeContext(AppContext(SupportedApp.INSTAGRAM, AppSection.DIRECT_MESSAGES, isDirectConversation = true))
        now = DmDwellPolicy.ENTRY_DELAY_MS
        val prompt = tracker.pollDmCheckin()!!
        assertTrue(prompt.isDmCheckin)
        assertTrue(tracker.interventionRequested(prompt.token))
        assertTrue(tracker.interventionLaunched(prompt.token))
        assertTrue(tracker.chooseIntervention(prompt.token, UserChoice.PERSONAL_CONFIRMED))
        assertEquals(InterventionPhase.NONE, tracker.interventionState().phase)
        assertEquals(EventOutcome.PERSONAL_CONFIRMED, tracker.storedEvents().single().outcome)
        val reels = AppContext(SupportedApp.INSTAGRAM, AppSection.REELS)
        tracker.observeContext(reels)
        assertNotNull(tracker.observe(RuntimeObservation("Instagram", "REELS",
            RelevanceResult(0.0, RelevanceStatus.IRRELEVANT, "short_form_video"), context = reels)))
    }
    @Test fun checkinsHaveSameActionsInBothModesAndSeparateStatistics() {
        FocusMode.entries.forEach { mode ->
            var now=0L
            val tracker=SessionTracker({now},wallClock={now})
            tracker.startSession("DBMS",60,mode); tracker.foregroundChanged(AppPackages.INSTAGRAM)
            tracker.observeContext(AppContext(SupportedApp.INSTAGRAM,AppSection.DIRECT_MESSAGES,isDirectConversation=true))
            now=600_000; val prompt=tracker.pollDmCheckin()!!
            assertTrue(tracker.interventionRequested(prompt.token)); assertTrue(tracker.interventionLaunched(prompt.token))
            assertFalse(tracker.chooseIntervention(prompt.token,UserChoice.CONTINUE_ANYWAY))
            assertTrue(tracker.chooseIntervention(prompt.token,UserChoice.WORK_CONFIRMED))
            assertEquals(0,tracker.statistics().attempts)
            val stats=tracker.dailyStatistics(0,Long.MAX_VALUE)
            assertEquals(1,stats.dmCheckins); assertEquals(1,stats.dmWorkConfirmed); assertEquals(0,stats.redirects)
            now+=599_999; assertNull(tracker.pollDmCheckin()); now++
            val next=tracker.pollDmCheckin()!!
            tracker.interventionRequested(next.token); tracker.interventionLaunched(next.token)
            tracker.chooseIntervention(next.token,UserChoice.GO_BACK)
            assertEquals(InterventionPhase.RETURNING_TO_FOCUS,tracker.interventionState().phase)
            tracker.confirmReturnToFocus()
            assertEquals(1,tracker.dailyStatistics(0,Long.MAX_VALUE).dmReturned)
            assertEquals(0,tracker.dailyStatistics(0,Long.MAX_VALUE).redirects)
        }
    }
    private fun epoch(value: String)=Instant.parse(value).toEpochMilli()
    @Test fun weekdayWrapAndBoundaryAreStrictlyFuture() {
        val monday=FocusSchedule(presetId="p",days=setOf(1),hour=9,minute=0)
        val zone=ZoneId.of("UTC")
        assertEquals(epoch("2026-10-05T09:00:00Z"),SchedulePolicy.nextFire(monday,epoch("2026-10-04T12:00:00Z"),zone))
        assertEquals(epoch("2026-10-12T09:00:00Z"),SchedulePolicy.nextFire(monday,epoch("2026-10-05T09:00:00Z"),zone))
    }
    @Test fun springGapMovesForwardAndFallOverlapFiresOnlyOnce() {
        val zone=ZoneId.of("America/New_York")
        val spring=FocusSchedule(presetId="p",days=setOf(7),hour=2,minute=30)
        assertEquals(epoch("2026-03-08T07:30:00Z"),SchedulePolicy.nextFire(spring,epoch("2026-03-08T05:00:00Z"),zone))
        val fall=spring.copy(hour=1)
        assertEquals(epoch("2026-11-01T05:30:00Z"),SchedulePolicy.nextFire(fall,epoch("2026-11-01T04:00:00Z"),zone))
        assertEquals(epoch("2026-11-08T06:30:00Z"),SchedulePolicy.nextFire(fall,epoch("2026-11-01T05:30:00Z"),zone))
    }
    @Test fun missedStartUsesRemainingTimeWithoutManufacturingStudy() {
        assertEquals(1_200_000,SchedulePolicy.remaining(25,0,300_000,false))
        assertEquals(0,SchedulePolicy.remaining(25,0,1_500_000,false))
        assertEquals(0,SchedulePolicy.remaining(25,0,1,true))
        assertEquals(0,SchedulePolicy.remaining(25,100,99,false))
        var now=300_000L
        val tracker=SessionTracker({now},wallClock={now})
        tracker.startScheduledSession("DBMS",25,FocusMode.LIGHT,1_200_000)
        assertEquals(1_200_000L,tracker.remainingMillis()); assertEquals(0L,tracker.statistics().studyMillis)
        now+=1_200_000; assertEquals(SessionStatus.COMPLETED,tracker.currentSession()!!.status)
        assertEquals(1_200_000L,tracker.dailyStatistics(0,Long.MAX_VALUE).studyMillis)
    }
    @Test fun historicalSessionAtScheduledTimePreventsLateReplacement() {
        var now=0L
        val tracker=SessionTracker({now},wallClock={now})
        tracker.startSession("DBMS",1)
        now=120_000; assertFalse(tracker.isSessionActive())
        assertTrue(tracker.occupiedAt(30_000)); assertFalse(tracker.occupiedAt(60_000))
        tracker.startSession("Coding",25); now+=30_000; tracker.endSession()
        assertTrue(tracker.occupiedAt(140_000)); assertFalse(tracker.occupiedAt(160_000))
    }
    @Test fun presetsValidateAndDeletingOneRemovesOnlyItsSchedules() {
        val store=MemorySessionStore(); FocusPreset.defaults.forEach(store::save)
        val p=store.presets().first(); store.save(FocusSchedule(id="s",presetId=p.id,days=setOf(1),hour=9,minute=0))
        store.deletePreset(p.id); assertEquals(1,store.presets().size); assertTrue(store.schedules().isEmpty())
        try { FocusPreset(name="bad",goal="DBMS",durationMinutes=0,mode=FocusMode.LIGHT); fail() } catch (_: IllegalArgumentException) {}
    }
}
