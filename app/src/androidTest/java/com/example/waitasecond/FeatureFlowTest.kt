package com.example.waitasecond

import android.content.Intent
import android.content.Context
import android.os.SystemClock
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import com.example.waitasecond.core.monitoring.*
import com.example.waitasecond.core.planning.*
import com.example.waitasecond.core.distraction.InterventionPhase
import com.example.waitasecond.core.session.FocusMode
import com.example.waitasecond.data.model.*
import com.example.waitasecond.data.repository.SessionManager
import com.example.waitasecond.service.scheduling.FocusScheduler
import com.example.waitasecond.ui.planning.PlanningActivity
import com.example.waitasecond.ui.retype.RetypeActivity
import com.example.waitasecond.ui.settings.FocusOptionsActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureFlowTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val scheduleIds=mutableListOf<String>()
    private fun reset() {
        val deadline=SystemClock.elapsedRealtime()+5000
        var loading=true
        while(loading && SystemClock.elapsedRealtime()<deadline) {
            instrumentation.runOnMainSync { loading=SessionManager.state.value?.loading != false }
            if(loading) SystemClock.sleep(20)
        }
        assertFalse(loading)
        instrumentation.runOnMainSync {
            SessionManager.attachStore(MemorySessionStore()); SessionManager.endSession()
            FocusPreset.defaults.forEach(SessionManager::savePreset)
        }
    }
    @After fun cleanup() { instrumentation.runOnMainSync {
        scheduleIds.forEach { SessionManager.deleteSchedule(it) }
        SessionManager.endSession()
    } }
    private fun startRetype(): Pair<String,String> {
        var pair: Pair<String,String>?=null
        instrumentation.runOnMainSync {
            SessionManager.startSession("Study DBMS",25,FocusMode.LIGHT)
            SessionManager.foregroundChanged(AppPackages.YOUTUBE)
            val token=SessionManager.observe(RuntimeObservation("YouTube","SHORTS",RelevanceResult(0.0,RelevanceStatus.IRRELEVANT,"short_form_video"),context=AppContext(SupportedApp.YOUTUBE,AppSection.SHORTS)))!!
            SessionManager.interventionRequested(token); SessionManager.interventionLaunched(token)
            SessionManager.chooseIntervention(token,UserChoice.CONTINUE_ANYWAY)
            pair=token to SessionManager.retypeSentence(token)!!
        }
        return pair!!
    }
    @Test fun typingScreenValidatesMatchAndDoesNotPersistDraft() {
        reset(); val (token,sentence)=startRetype()
        val intent=Intent(context,RetypeActivity::class.java).putExtra(RetypeActivity.EXTRA_TOKEN,token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<RetypeActivity>(intent).use { scenario ->
            onView(withId(R.id.retypeContinue)).check(matches(isNotEnabled()))
            onView(withId(R.id.retypeInput)).perform(replaceText("wrong"),closeSoftKeyboard())
            onView(withId(R.id.retypeContinue)).check(matches(isNotEnabled()))
            scenario.recreate()
            onView(withId(R.id.retypeInput)).check(matches(withText("")))
            onView(withId(R.id.retypeInput)).perform(replaceText(sentence.lowercase()),closeSoftKeyboard())
            onView(withId(R.id.retypeContinue)).check(matches(isEnabled())).perform(scrollTo(),click())
            instrumentation.runOnMainSync {
                assertEquals(EventOutcome.CONTINUED_AFTER_RETYPE,SessionManager.storedEvents().last().outcome)
                assertEquals(1,SessionManager.distractionAttempts())
            }
        }
    }
    @Test fun leavingTypingReusesTheSameIntervention() {
        reset(); val (token,_)=startRetype()
        ActivityScenario.launch<RetypeActivity>(Intent(context,RetypeActivity::class.java).putExtra(RetypeActivity.EXTRA_TOKEN,token)).use { scenario ->
            scenario.onActivity { it.finish() }
            instrumentation.waitForIdleSync()
        }
        instrumentation.runOnMainSync {
            assertEquals(InterventionPhase.INTERVENTION,SessionManager.interventionState().phase)
            assertEquals(token,SessionManager.interventionState().token)
            assertEquals(1,SessionManager.distractionAttempts())
        }
    }
    @Test fun presetsAppearAndCanStartFromHomeManagement() {
        reset()
        ActivityScenario.launch(PlanningActivity::class.java).use {
            onView(withText("Study DBMS · 25 min · light\nStudy DBMS")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Deep Work · 60 min · strict\nDeep Work")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(org.hamcrest.Matchers.allOf(withText(R.string.start_preset),hasSibling(withText("Study DBMS · 25 min · light\nStudy DBMS")))).perform(scrollTo(),click())
            instrumentation.runOnMainSync { assertEquals("Study DBMS",SessionManager.activeSession()!!.goal) }
        }
    }
    @Test fun deliveredScheduleStartsOnlyRemainingTimeAndIgnoresDuplicateOrDisabledDelivery() {
        reset()
        instrumentation.runOnMainSync {
            val preset=SessionManager.presets().first()
            val fire=System.currentTimeMillis()-300_000
            val schedule=FocusSchedule(presetId=preset.id,days=setOf(1,2,3,4,5,6,7),hour=9,minute=0,nextFireAt=fire)
            scheduleIds+=schedule.id
            SessionManager.updateScheduleTime(schedule)
            FocusScheduler.deliver(context,schedule.id,fire)
            val session=SessionManager.activeSession()!!
            assertTrue(SessionManager.remainingMillis() in 1_199_000L..1_200_000L)
            assertEquals(0,SessionManager.statistics().attempts)
            FocusScheduler.deliver(context,schedule.id,fire)
            assertEquals(session.id,SessionManager.activeSession()!!.id)
            SessionManager.endSession()
            SessionManager.updateScheduleTime(schedule.copy(enabled=false))
            FocusScheduler.deliver(context,schedule.id,fire)
            assertFalse(SessionManager.isSessionActive())
        }
    }

    @Test fun focusOptionsAreOptInAndPersistAcrossRecreation() {
        val preferences = context.getSharedPreferences("focus_options", Context.MODE_PRIVATE)
        val keys = listOf("grayscale_guide", "trusted_contacts_guide")
        val previous = keys.associateWith { if (preferences.contains(it)) preferences.getBoolean(it, false) else null }
        try {
            preferences.edit().apply { keys.forEach { putBoolean(it, false) } }.apply()
            ActivityScenario.launch(FocusOptionsActivity::class.java).use { scenario ->
                onView(withId(R.id.grayscaleGuide)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withId(R.id.trustedContactsGuide)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withId(R.id.grayscaleOptIn)).perform(scrollTo(), click())
                scenario.onActivity { it.findViewById<SwitchCompat>(R.id.trustedContactsOptIn).performClick() }
                scenario.recreate()
                onView(withId(R.id.grayscaleOptIn)).check(matches(isChecked()))
                onView(withId(R.id.trustedContactsOptIn)).check(matches(isChecked()))
                onView(withId(R.id.grayscaleAndroidSettings)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
                onView(withId(R.id.addTrustedContact)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
            }
        } finally {
            preferences.edit().apply {
                previous.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) }
            }.apply()
        }
    }
}
