package com.example.waitasecond

import com.example.waitasecond.data.repository.SessionManager
import com.example.waitasecond.ui.home.MainActivity
import com.example.waitasecond.core.session.FocusMode
import com.example.waitasecond.core.distraction.*
import com.example.waitasecond.core.monitoring.*
import com.example.waitasecond.data.model.*

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FocusFlowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    @After fun cleanup() { instrumentation.runOnMainSync { SessionManager.endSession() } }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun reset() {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var loading = true
        while (loading && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync { loading = SessionManager.state.value?.loading != false }
            if (loading) SystemClock.sleep(20)
        }
        assertFalse("Session storage failed to initialize", loading)
        instrumentation.runOnMainSync {
            // Test interactions must never create fake history in the user's SQLite database.
            SessionManager.attachStore(MemorySessionStore())
            SessionManager.endSession()
        }
    }

    @Test fun homeTimerStatisticsAndRecreationStayConnected() {
        reset()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.topicValue)).check(matches(withText("DBMS")))
            screenshot("focus-home.png")
            onView(withId(R.id.modeLight)).perform(scrollTo(), click()).check(matches(isChecked()))
            onView(withId(R.id.startSessionButton)).perform(scrollTo()).check(matches(isEnabled())).perform(click())
            var first = 0L
            scenario.onActivity { first = SessionManager.remainingMillis(); assertTrue(SessionManager.isSessionActive()) }
            SystemClock.sleep(1_200)
            scenario.onActivity { assertTrue(SessionManager.remainingMillis() < first) }
            onView(withId(R.id.navStatistics)).perform(click())
            onView(withId(R.id.statisticsPage)).check(matches(isDisplayed()))
            onView(withId(R.id.statsAttemptValue)).perform(scrollTo()).check(matches(isDisplayed()))
            screenshot("focus-statistics-empty.png")
            scenario.recreate()
            onView(withId(R.id.statisticsPage)).check(matches(isDisplayed()))
            scenario.onActivity { assertTrue(SessionManager.isSessionActive()) }
            onView(withId(R.id.navHome)).perform(click())
            onView(withId(R.id.endSessionButton)).perform(scrollTo(), click())
            scenario.onActivity { assertFalse(SessionManager.isSessionActive()) }
        }
    }

    @Test fun editableTopicAndDurationAreUsedByTheActualSession() {
        reset()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.editTopicButton)).perform(scrollTo(), click())
            onView(isAssignableFrom(android.widget.EditText::class.java)).perform(replaceText("SQL joins"), closeSoftKeyboard())
            onView(withText(R.string.save)).perform(click())
            onView(withId(R.id.durationButton)).perform(scrollTo(), click())
            onView(isAssignableFrom(android.widget.EditText::class.java)).perform(replaceText("1"), closeSoftKeyboard())
            onView(withText(R.string.save)).perform(click())
            onView(withId(R.id.modeLight)).perform(scrollTo(), click()).check(matches(isChecked()))
            onView(withId(R.id.startSessionButton)).perform(scrollTo()).check(matches(isEnabled())).perform(click())
            scenario.onActivity {
                assertEquals("Study SQL joins", SessionManager.activeSession()!!.goal)
                assertEquals(1, SessionManager.activeSession()!!.durationMinutes)
            }
        }
    }

    @Test fun activeTimerSurvivesRecreation() {
        reset()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.modeLight)).perform(scrollTo(), click()).check(matches(isChecked()))
            onView(withId(R.id.startSessionButton)).perform(scrollTo()).check(matches(isEnabled())).perform(click())
            var remaining = 0L
            scenario.onActivity { remaining = SessionManager.remainingMillis() }
            scenario.recreate()
            SystemClock.sleep(1_200)
            scenario.onActivity {
                assertTrue(SessionManager.remainingMillis() < remaining)
                assertTrue(SessionManager.isSessionActive())
            }
        }
    }
    @Test fun strictResetSurvivesRecreationConsumesBackAndCanEndSession() {
        reset()
        instrumentation.runOnMainSync {
            SessionManager.startSession("DBMS", 25, FocusMode.STRICT)
            SessionManager.foregroundChanged(AppPackages.YOUTUBE)
            val token = SessionManager.observe(RuntimeObservation("YouTube", "GTA Gameplay",
                RelevanceResult(0.0, RelevanceStatus.IRRELEVANT, "off_topic_video"), isVideo = true,
                context = AppContext(SupportedApp.YOUTUBE, AppSection.VIDEO)))!!
            assertTrue(SessionManager.interventionRequested(token))
            assertTrue(SessionManager.interventionLaunched(token))
            assertTrue(SessionManager.chooseIntervention(token, UserChoice.GO_BACK))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.focusResetPage)).check(matches(isDisplayed()))
            var before = 0L
            scenario.onActivity { before = SessionManager.countdownRemainingMillis() }
            scenario.recreate()
            onView(withId(R.id.focusResetPage)).check(matches(isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
            scenario.onActivity {
                assertEquals(InterventionPhase.STRICT_FOCUS_RESET, SessionManager.interventionState().phase)
                assertTrue(SessionManager.countdownRemainingMillis() <= before)
                assertEquals(EventOutcome.PENDING, SessionManager.storedEvents().last().outcome)
            }
            onView(withId(R.id.resetEndSession)).perform(scrollTo(), click())
            scenario.onActivity {
                assertFalse(SessionManager.isSessionActive())
                assertEquals(InterventionPhase.NONE, SessionManager.interventionState().phase)
                assertEquals(EventOutcome.INTERRUPTED, SessionManager.storedEvents().last().outcome)
            }
        }
    }

}
