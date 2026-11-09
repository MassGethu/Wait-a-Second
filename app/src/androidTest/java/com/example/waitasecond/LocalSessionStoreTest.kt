package com.example.waitasecond

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waitasecond.core.monitoring.AppSection
import com.example.waitasecond.core.monitoring.ShortVideoBucket
import com.example.waitasecond.core.monitoring.ShortVideoMetrics
import com.example.waitasecond.core.monitoring.ShortVideoPlatform
import com.example.waitasecond.core.monitoring.SupportedApp
import com.example.waitasecond.core.planning.*
import com.example.waitasecond.core.session.FocusSession
import com.example.waitasecond.core.session.FocusMode
import com.example.waitasecond.core.session.SessionStatus
import com.example.waitasecond.data.local.LocalSessionStore
import com.example.waitasecond.data.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.time.LocalDate
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class LocalSessionStoreTest {
    @Test fun sqliteRoundTripPreservesSessionsChoicesAndIdempotentUpdates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${UUID.randomUUID()}.db"
        val executor = Executor { it.run() } // This test runs off the UI thread.
        val failures = mutableListOf<Exception>()
        val session = FocusSession("DBMS", 25, 123, SessionStatus.CANCELLED, startedAt = 1_000,
            accumulatedMillis = 500)
        val record = SessionRecord(session, listOf(TimeInterval(1_000, 1_500)),
            listOf(TimeInterval(1_200, 1_500, SupportedApp.YOUTUBE)), 500, 1)
        val event = DistractionEvent(timestamp = 1_300, sessionId = session.id, studyTopic = session.goal,
            app = SupportedApp.YOUTUBE, section = AppSection.SHORTS, category = "short_form_video")
        try {
            LocalSessionStore(context, executor, { failures.add(it) }, name).use { store ->
                store.load(); store.save(record); store.save(event)
                store.save(event.copy(userChoice = UserChoice.CONTINUE_ANYWAY))
            }
            LocalSessionStore(context, executor, { failures.add(it) }, name).use { restored ->
                restored.load()
                assertEquals(listOf(record), restored.sessions())
                assertEquals(listOf(event.copy(userChoice = UserChoice.CONTINUE_ANYWAY)), restored.events())
            }
            assertTrue(failures.toString(), failures.isEmpty())
        } finally { context.deleteDatabase(name) }
    }
    @Test fun malformedRowDoesNotEraseValidRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${UUID.randomUUID()}.db"
        val failures = mutableListOf<Exception>()
        val executor = Executor { it.run() }
        try {
            LocalSessionStore(context, executor, { failures.add(it) }, name).use { it.load() }
            context.openOrCreateDatabase(name, 0, null).use {
                it.execSQL("INSERT INTO sessions VALUES ('bad', 'not json')")
            }
            LocalSessionStore(context, executor, { failures.add(it) }, name).use { store ->
                store.load()
                assertTrue(store.sessions().isEmpty())
                assertEquals(1, failures.size)
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun versionOnePayloadsLoadWithoutErasingHistoryAndUpgradeOnWrite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${UUID.randomUUID()}.db"
        val executor = Executor { it.run() }
        val failures = mutableListOf<Exception>()
        val record = SessionRecord(FocusSession("DBMS", 25, 0, SessionStatus.CANCELLED), emptyList(), emptyList(), 0, 1)
        val event = DistractionEvent(timestamp = 123, sessionId = record.session.id, studyTopic = "DBMS",
            app = SupportedApp.INSTAGRAM, section = AppSection.REELS, category = "legacy", userChoice = UserChoice.RETURN_TO_SESSION,
            returnedToFocus = true)
        val oldRecord = LocalSessionStore.encodeRecord(record).apply {
            remove("payloadVersion"); remove("mode"); remove("endedAt"); put("status", "PAUSED")
        }
        val oldEvent = LocalSessionStore.encodeEvent(event).apply {
            remove("payloadVersion"); remove("mode"); remove("outcome"); remove("actionAt"); remove("resolvedAt")
        }
        try {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
                db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
                db.execSQL("INSERT INTO sessions VALUES (?, ?)", arrayOf(record.session.id, oldRecord.toString()))
                db.execSQL("INSERT INTO events VALUES (?, ?)", arrayOf(event.id, oldEvent.toString()))
                db.version = 1
            }
            LocalSessionStore(context, executor, { failures.add(it) }, name).use { store ->
                store.load()
                assertEquals(FocusMode.LIGHT, store.sessions().single().session.mode)
                assertEquals(SessionStatus.CANCELLED, store.sessions().single().session.status)
                assertEquals(FocusPreset.defaults,store.presets())
                assertEquals(EventOutcome.RETURNED_TO_FOCUS, store.events().single().outcome)
                store.save(store.sessions().single().copy(session = store.sessions().single().session.copy(mode = FocusMode.STRICT)))
            }
            LocalSessionStore(context, executor, { failures.add(it) }, name).use { store ->
                store.load()
                assertEquals(FocusMode.STRICT, store.sessions().single().session.mode)
                assertEquals(event.id, store.events().single().id)
                assertEquals(1, store.events().size)
                assertEquals(2,store.presets().size)
            }
            assertTrue(failures.toString(), failures.isEmpty())
        } finally { context.deleteDatabase(name) }
    }
    @Test fun planningRowsAndCheckinsRoundTripAndDeletedDefaultsDoNotReseed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${UUID.randomUUID()}.db"
        val failures = mutableListOf<Exception>()
        val executor = Executor { it.run() }
        val preset = FocusPreset(name="Custom",goal="Coding",durationMinutes=999,mode=FocusMode.STRICT)
        val schedule = FocusSchedule(presetId=preset.id,days=setOf(1,7),hour=23,minute=59,nextFireAt=123,lastHandledAt=100)
        val event = DistractionEvent(timestamp=100,sessionId="session",studyTopic="Coding",app=SupportedApp.INSTAGRAM,
            section=AppSection.DIRECT_MESSAGES,category="dm_dwell",kind=EventKind.DM_CHECKIN,outcome=EventOutcome.WORK_CONFIRMED)
        try {
            LocalSessionStore(context,executor,{ failures.add(it) },name).use { store ->
                store.load(); FocusPreset.defaults.forEach { store.deletePreset(it.id) }
                store.save(preset); store.save(schedule); store.save(event)
            }
            LocalSessionStore(context,executor,{ failures.add(it) },name).use { store ->
                store.load(); assertEquals(listOf(preset),store.presets()); assertEquals(listOf(schedule),store.schedules())
                assertEquals(listOf(event),store.events()); store.deletePreset(preset.id)
            }
            LocalSessionStore(context,executor,{ failures.add(it) },name).use { store ->
                store.load(); assertTrue(store.presets().isEmpty()); assertTrue(store.schedules().isEmpty()); assertEquals(listOf(event),store.events())
            }
            context.openOrCreateDatabase(name,0,null).use { assertEquals(3,it.version) }
            assertTrue(failures.toString(),failures.isEmpty())
        } finally { context.deleteDatabase(name) }
    }

    @Test fun shortVideoMigrationAndAbsoluteCheckpointsPreserveExistingRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${UUID.randomUUID()}.db"
        val failures = mutableListOf<Exception>()
        val executor = Executor { it.run() }
        val date = LocalDate.of(2026, 11, 8)
        val outside = ShortVideoBucket(date, ShortVideoPlatform.REELS, null)
        val focused = ShortVideoBucket(date, ShortVideoPlatform.REELS, "session")
        val totals = mapOf(outside to ShortVideoMetrics(600, 1, 2), focused to ShortVideoMetrics(1000, 0, 1))
        try {
            LocalSessionStore(context, executor, { failures.add(it) }, name).use { it.load() }
            context.openOrCreateDatabase(name, 0, null).use {
                it.execSQL("DROP TABLE short_video_totals")
                it.version = 2
            }
            LocalSessionStore(context, executor, { failures.add(it) }, name).use { store ->
                store.load()
                assertEquals(FocusPreset.defaults, store.presets())
                store.saveShortVideoTotals(totals)
                store.saveShortVideoTotals(totals)
            }
            LocalSessionStore(context, executor, { failures.add(it) }, name).use { store ->
                store.load()
                assertEquals(totals, store.shortVideoTotals())
                assertEquals(FocusPreset.defaults, store.presets())
            }
            assertTrue(failures.toString(), failures.isEmpty())
        } finally { context.deleteDatabase(name) }
    }

}
