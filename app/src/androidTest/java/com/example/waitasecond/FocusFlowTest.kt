package com.example.waitasecond

import android.content.Context
import android.content.Intent
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
    private fun reset() { instrumentation.runOnMainSync { SessionManager.endSession() } }

    @Test fun homeTimerStatisticsAndRecreationStayConnected() {
        reset()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.topicValue)).check(matches(withText("DBMS")))
            screenshot("focus-home.png")
            onView(withId(R.id.startSessionButton)).perform(scrollTo(), click())
            var first = 0L
            scenario.onActivity { first = SessionManager.remainingMillis(); assertTrue(SessionManager.isSessionActive()) }
            SystemClock.sleep(1_200)
            scenario.onActivity { assertTrue(SessionManager.remainingMillis() < first) }
            onView(withId(R.id.navStatistics)).perform(click())
            onView(withId(R.id.statisticsPage)).check(matches(isDisplayed()))
            onView(withId(R.id.statsAttemptValue)).check(matches(withText("0")))
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
            onView(withId(R.id.startSessionButton)).perform(scrollTo(), click())
            scenario.onActivity {
                assertEquals("Study SQL joins", SessionManager.activeSession()!!.goal)
                assertEquals(1, SessionManager.activeSession()!!.durationMinutes)
            }
        }
    }

    private fun interventionIntent(): Intent {
        lateinit var token: String
        instrumentation.runOnMainSync {
            SessionManager.startSession("Study SQL joins", 25)
            SessionManager.foregroundChanged(SessionTracker.YOUTUBE_PACKAGE)
            token = SessionManager.observe(RuntimeObservation("YouTube", "GTA Gameplay",
                DbmsRelevanceScorer.score("GTA Gameplay"), isVideo = true))!!
            SessionManager.interventionRequested(token)
        }
        return Intent(context, InterventionActivity::class.java).apply {
            putExtra(InterventionActivity.EXTRA_CONTENT_KEY, token)
            putExtra(InterventionActivity.EXTRA_TARGET_PACKAGE, SessionTracker.YOUTUBE_PACKAGE)
            putExtra(InterventionActivity.EXTRA_CONTENT, "GTA Gameplay")
        }
    }

    @Test fun continueKeepsTimerAndAttemptAndStatisticsUpdate() {
        reset()
        ActivityScenario.launch(MainActivity::class.java).use {
            ActivityScenario.launch<InterventionActivity>(interventionIntent()).use {
                onView(withId(R.id.interventionGoal)).check(matches(withText("SQL joins")))
                onView(withId(R.id.interventionSource)).check(matches(withText("YouTube")))
                screenshot("focus-overlay.png")
                onView(withId(R.id.continueButton)).perform(scrollTo(), click())
            }
            instrumentation.runOnMainSync {
                assertTrue(SessionManager.isSessionActive())
                assertEquals(1, SessionManager.distractionAttempts())
                assertEquals(0, SessionManager.statistics().redirects)
            }
            onView(withId(R.id.navStatistics)).perform(click())
            onView(withId(R.id.statsAttemptValue)).check(matches(withText("1")))
            screenshot("focus-statistics-attempt.png")
        }
    }

    @Test fun returnLaunchesTargetAppWithoutEndingFocusSession() {
        reset()
        // The controlled device has YouTube installed. Resolve before testing navigation.
        org.junit.Assume.assumeNotNull(context.packageManager.getLaunchIntentForPackage(SessionTracker.YOUTUBE_PACKAGE))
        ActivityScenario.launch<InterventionActivity>(interventionIntent()).use {
            onView(withId(R.id.goBackButton)).perform(scrollTo(), click())
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(SessionManager.isSessionActive())
                assertEquals(1, SessionManager.distractionAttempts())
                assertFalse(SessionManager.isInterventionVisible())
            }
        }
    }
}
