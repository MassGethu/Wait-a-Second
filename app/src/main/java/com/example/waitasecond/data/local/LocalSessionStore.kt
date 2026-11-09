package com.example.waitasecond.data.local

import com.example.waitasecond.core.monitoring.AppSection
import com.example.waitasecond.core.monitoring.SupportedApp
import com.example.waitasecond.core.monitoring.ShortVideoBucket
import com.example.waitasecond.core.monitoring.ShortVideoMetrics
import com.example.waitasecond.core.monitoring.ShortVideoPlatform
import com.example.waitasecond.core.session.FocusSession
import com.example.waitasecond.core.session.FocusMode
import com.example.waitasecond.data.model.EventOutcome
import com.example.waitasecond.core.session.SessionStatus
import com.example.waitasecond.data.model.DistractionEvent
import com.example.waitasecond.data.model.MemorySessionStore
import com.example.waitasecond.data.model.SessionRecord
import com.example.waitasecond.data.model.TimeInterval
import com.example.waitasecond.data.model.UserChoice

import com.example.waitasecond.core.planning.*
import com.example.waitasecond.data.model.EventKind
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.Executor

/** Main-thread cache, serialized SQLite writes on one worker. IDs make retries idempotent. */
class LocalSessionStore(context: Context, private val executor: Executor,
    private val onFailure: (Exception) -> Unit, databaseName: String = "focus.db") : MemorySessionStore(), java.io.Closeable {
    private val pendingShortVideoTotals = mutableMapOf<ShortVideoBucket, ShortVideoMetrics>() // worker only
    private val helper = object : SQLiteOpenHelper(context, databaseName, null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            createPlanning(db)
            createShortVideo(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createPlanning(db)
            if (oldVersion < 3) createShortVideo(db)
        }
    }
    private fun createShortVideo(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE short_video_totals (
            day TEXT NOT NULL, platform TEXT NOT NULL, session_id TEXT NOT NULL,
            viewer_millis INTEGER NOT NULL, episodes INTEGER NOT NULL, transitions INTEGER NOT NULL,
            PRIMARY KEY (day, platform, session_id))""")
    }
    private fun createPlanning(db: SQLiteDatabase) {
        listOf("presets", "schedules", "schedule_runs").forEach {
            db.execSQL("CREATE TABLE $it (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        }
        FocusPreset.defaults.forEach { preset ->
            db.insertOrThrow("presets", null, ContentValues().apply {
                put("id", preset.id); put("payload", encodePreset(preset).toString())
            })
        }
    }
    /** Called on the worker before this cache is published to the main thread. */
    fun load() {
        fun read(table: String, consume: (JSONObject) -> Unit) {
            helper.readableDatabase.query(table, arrayOf("payload"), null, null, null, null, "rowid").use { cursor ->
                while (cursor.moveToNext()) {
                    try { consume(JSONObject(cursor.getString(0))) }
                    catch (error: Exception) { onFailure(error) } // Keep valid rows; never erase a damaged database.
                }
            }
        }
        read("sessions") { json -> super.save(decodeRecord(json)) }
        read("events") { json -> super.save(decodeEvent(json)) }
        read("presets") { super.save(decodePreset(it)) }
        read("schedules") { super.save(decodeSchedule(it)) }
        read("schedule_runs") { super.save(ScheduleRun(it.getString("id"), it.getString("scheduleId"), it.getLong("fireAt"), it.getString("result"), it.optString("sessionId").takeIf(String::isNotEmpty))) }
        val shortVideo = mutableMapOf<ShortVideoBucket, ShortVideoMetrics>()
        helper.readableDatabase.query("short_video_totals",
            arrayOf("day", "platform", "session_id", "viewer_millis", "episodes", "transitions"),
            null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    val bucket = ShortVideoBucket(LocalDate.parse(cursor.getString(0)), ShortVideoPlatform.valueOf(cursor.getString(1)),
                        cursor.getString(2).ifEmpty { null })
                    shortVideo[bucket] = ShortVideoMetrics(cursor.getLong(3), cursor.getInt(4), cursor.getInt(5))
                } catch (error: Exception) { onFailure(error) }
            }
        }
        super.saveShortVideoTotals(shortVideo)
    }
    override fun saveShortVideoTotals(totals: Map<ShortVideoBucket, ShortVideoMetrics>) {
        super.saveShortVideoTotals(totals)
        executor.execute {
            pendingShortVideoTotals.putAll(totals)
            try {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    pendingShortVideoTotals.forEach { (bucket, metrics) ->
                        check(db.insertWithOnConflict("short_video_totals", null, ContentValues().apply {
                            put("day", bucket.date.toString()); put("platform", bucket.platform.name)
                            put("session_id", bucket.sessionId.orEmpty()); put("viewer_millis", metrics.viewerMillis)
                            put("episodes", metrics.episodes); put("transitions", metrics.transitions)
                        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Short video totals could not be saved" }
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                pendingShortVideoTotals.clear()
            } catch (error: Exception) { onFailure(error) }
        }
    }
    override fun save(record: SessionRecord) {
        super.save(record)
        persist("sessions", record.session.id, encodeRecord(record).toString())
    }
    override fun save(event: DistractionEvent) {
        super.save(event)
        persist("events", event.id, encodeEvent(event).toString())
    }
    override fun save(preset: FocusPreset) { super.save(preset); persist("presets", preset.id, encodePreset(preset).toString()) }
    override fun save(schedule: FocusSchedule) { super.save(schedule); persist("schedules", schedule.id, encodeSchedule(schedule).toString()) }
    override fun save(run: ScheduleRun) { super.save(run); persist("schedule_runs", run.id, JSONObject().put("id",run.id).put("scheduleId",run.scheduleId).put("fireAt",run.fireAt).put("result",run.result).put("sessionId",run.sessionId).toString()) }
    override fun deletePreset(id: String) {
        schedules().filter { it.presetId == id }.forEach { deleteSchedule(it.id) }
        super.deletePreset(id); delete("presets", id)
    }
    override fun deleteSchedule(id: String) { super.deleteSchedule(id); delete("schedules", id) }
    private fun delete(table: String, id: String) { executor.execute {
        try { helper.writableDatabase.delete(table, "id=?", arrayOf(id)) } catch (e: Exception) { onFailure(e) }
    } }
    private fun persist(table: String, id: String, payload: String) {
        executor.execute {
            try {
                helper.writableDatabase.insertWithOnConflict(table, null, ContentValues().apply {
                    put("id", id); put("payload", payload)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Database write failed" } }
            } catch (error: Exception) { onFailure(error) }
        }
    }
    /** Caller must drain its worker before closing. Application-owned store lives for the process. */
    override fun close() { helper.close() }
    companion object {
        fun encodePreset(p: FocusPreset) = JSONObject().put("id",p.id).put("name",p.name).put("goal",p.goal).put("minutes",p.durationMinutes).put("mode",p.mode.name)
        fun decodePreset(j: JSONObject) = FocusPreset(j.getString("id"), j.getString("name"), j.getString("goal"), j.getInt("minutes"), FocusMode.valueOf(j.getString("mode")))
        fun encodeSchedule(s: FocusSchedule) = JSONObject().put("id",s.id).put("presetId",s.presetId).put("days",JSONArray(s.days.sorted())).put("hour",s.hour).put("minute",s.minute).put("enabled",s.enabled).put("next",s.nextFireAt).put("handled",s.lastHandledAt)
        fun decodeSchedule(j: JSONObject): FocusSchedule {
            val days = j.getJSONArray("days")
            return FocusSchedule(j.getString("id"),j.getString("presetId"),(0 until days.length()).map { days.getInt(it) }.toSet(),j.getInt("hour"),j.getInt("minute"),j.getBoolean("enabled"),if(j.has("next")) j.getLong("next") else null,if(j.has("handled")) j.getLong("handled") else null)
        }
        private fun intervals(values: List<TimeInterval>) = JSONArray().apply {
            values.forEach { put(JSONObject().put("start", it.start).put("end", it.end).put("app", it.app?.name)) }
        }
        private fun decodeIntervals(array: JSONArray): List<TimeInterval> = (0 until array.length()).map {
            val json = array.getJSONObject(it)
            TimeInterval(json.getLong("start"), json.getLong("end"),
                json.optString("app").takeIf { name -> name.isNotEmpty() }?.let(SupportedApp::valueOf))
        }
        fun encodeRecord(record: SessionRecord): JSONObject = record.session.let { s -> JSONObject()
            .put("id", s.id).put("goal", s.goal).put("minutes", s.durationMinutes)
            .put("payloadVersion", 2).put("mode", s.mode.name).put("endedAt", s.endedAt)
            .put("scheduledDuration", s.scheduledDurationMillis)
            .put("startMono", s.startTimeMillis).put("status", s.status.name).put("startedAt", s.startedAt)
            .put("accumulated", s.accumulatedMillis).put("resumeMono", s.resumedAtMillis).put("resumeEpoch", s.resumedAtEpoch)
            .put("study", intervals(record.studyIntervals)).put("apps", intervals(record.appIntervals))
            .put("checkpoint", record.checkpointElapsed).put("boot", record.bootCount) }
        fun decodeRecord(j: JSONObject): SessionRecord {
            // PAUSED existed in early payloads. It is terminal on upgrade so an old paused
            // session cannot unexpectedly restart after the feature's removal.
            val status = j.getString("status").let {
                if (it == "PAUSED") SessionStatus.CANCELLED else SessionStatus.valueOf(it)
            }
            val s = FocusSession(j.getString("goal"), j.getInt("minutes"), j.getLong("startMono"),
                status, j.getString("id"), j.getLong("startedAt"),
                j.getLong("accumulated"), j.getLong("resumeMono"), j.getLong("resumeEpoch"),
                FocusMode.valueOf(j.optString("mode", "LIGHT")), if (j.has("endedAt")) j.getLong("endedAt") else null,
                if (j.has("scheduledDuration")) j.getLong("scheduledDuration") else null)
            require(s.durationMinutes in 1..999 && s.accumulatedMillis in 0..s.plannedMillis)
            return SessionRecord(s, decodeIntervals(j.getJSONArray("study")), decodeIntervals(j.getJSONArray("apps")),
                j.getLong("checkpoint"), j.getInt("boot"))
        }
        fun encodeEvent(e: DistractionEvent): JSONObject = JSONObject().put("id", e.id).put("timestamp", e.timestamp)
            .put("sessionId", e.sessionId).put("topic", e.studyTopic).put("app", e.app.name)
            .put("section", e.section.name).put("category", e.category).put("choice", e.userChoice.name)
            .put("returned", e.returnedToFocus).put("payloadVersion", 2).put("mode", e.mode.name)
            .put("kind", e.kind.name).put("outcome", e.outcome.name).put("actionAt", e.actionAt).put("resolvedAt", e.resolvedAt)
        fun decodeEvent(j: JSONObject) = DistractionEvent(j.getString("id"), j.getLong("timestamp"),
            j.getString("sessionId"), j.getString("topic"), SupportedApp.valueOf(j.getString("app")),
            AppSection.valueOf(j.getString("section")), j.getString("category"),
            UserChoice.valueOf(j.getString("choice")), j.getBoolean("returned"),
            FocusMode.valueOf(j.optString("mode", "LIGHT")),
            EventOutcome.valueOf(j.optString("outcome", when {
                j.getBoolean("returned") -> "RETURNED_TO_FOCUS"
                j.getString("choice") == "CONTINUE_ANYWAY" -> "CONTINUED_DISTRACTION"
                j.getString("choice") == "AUTO_DISMISSED" -> "INTERRUPTED"
                else -> "PENDING"
            })), if (j.has("actionAt")) j.getLong("actionAt") else null,
            if (j.has("resolvedAt")) j.getLong("resolvedAt") else null, EventKind.valueOf(j.optString("kind", "DISTRACTION")))
    }
}
