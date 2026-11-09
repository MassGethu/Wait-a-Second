package com.example.waitasecond.data.model

import com.example.waitasecond.core.planning.ScheduleRun
import com.example.waitasecond.core.planning.FocusSchedule
import com.example.waitasecond.core.planning.FocusPreset
import com.example.waitasecond.core.monitoring.AppSection
import com.example.waitasecond.core.monitoring.SupportedApp
import com.example.waitasecond.core.monitoring.ShortVideoBucket
import com.example.waitasecond.core.monitoring.ShortVideoMetrics
import com.example.waitasecond.core.session.FocusSession
import com.example.waitasecond.core.session.FocusMode

import java.util.UUID

enum class EventKind { DISTRACTION, DM_CHECKIN }

enum class EventOutcome { WORK_CONFIRMED, PENDING, RETURNED_TO_FOCUS, CONTINUED_DISTRACTION, CONTINUED_AFTER_RETYPE, RESET_COMPLETED, INTERRUPTED, PERSONAL_CONFIRMED }

enum class UserChoice { WORK_CONFIRMED, GO_BACK, RETURN_TO_SESSION, CONTINUE_ANYWAY, AUTO_DISMISSED, UNKNOWN, PERSONAL_CONFIRMED }
/** No titles, channels, message text, or raw accessibility snapshots are persisted. */
data class DistractionEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long,
    val sessionId: String,
    val studyTopic: String,
    val app: SupportedApp,
    val section: AppSection,
    val category: String,
    val userChoice: UserChoice = UserChoice.UNKNOWN,
    val returnedToFocus: Boolean = false,
    val mode: FocusMode = FocusMode.LIGHT,
    val outcome: EventOutcome = if (returnedToFocus) EventOutcome.RETURNED_TO_FOCUS else EventOutcome.PENDING,
    val actionAt: Long? = null,
    val resolvedAt: Long? = null,
    val kind: EventKind = EventKind.DISTRACTION
)
data class TimeInterval(val start: Long, val end: Long, val app: SupportedApp? = null) {
    fun overlap(from: Long, until: Long) = (minOf(end, until) - maxOf(start, from)).coerceAtLeast(0)
}
data class SessionRecord(
    val session: FocusSession,
    val studyIntervals: List<TimeInterval>,
    val appIntervals: List<TimeInterval>,
    val checkpointElapsed: Long,
    val bootCount: Int
)
interface SessionStore {
    fun presets(): List<FocusPreset>
    fun schedules(): List<FocusSchedule>
    fun runs(): List<ScheduleRun>
    fun save(preset: FocusPreset)
    fun save(schedule: FocusSchedule)
    fun save(run: ScheduleRun)
    fun deletePreset(id: String)
    fun deleteSchedule(id: String)
    fun sessions(): List<SessionRecord>
    fun events(): List<DistractionEvent>
    fun save(record: SessionRecord)
    fun save(event: DistractionEvent)
    fun shortVideoTotals(): Map<ShortVideoBucket, ShortVideoMetrics>
    fun saveShortVideoTotals(totals: Map<ShortVideoBucket, ShortVideoMetrics>)
}
open class MemorySessionStore : SessionStore {
    private val presetMap = linkedMapOf<String, FocusPreset>()
    private val scheduleMap = linkedMapOf<String, FocusSchedule>()
    private val runMap = linkedMapOf<String, ScheduleRun>()
    override fun presets() = presetMap.values.toList()
    override fun schedules() = scheduleMap.values.toList()
    override fun runs() = runMap.values.toList()
    override fun save(preset: FocusPreset) { presetMap[preset.id] = preset }
    override fun save(schedule: FocusSchedule) { scheduleMap[schedule.id] = schedule }
    override fun save(run: ScheduleRun) { runMap[run.id] = run }
    override fun deletePreset(id: String) { presetMap.remove(id); scheduleMap.values.filter { it.presetId == id }.map { it.id }.forEach(::deleteSchedule) }
    override fun deleteSchedule(id: String) { scheduleMap.remove(id) }
    protected val records = linkedMapOf<String, SessionRecord>()
    protected val distractions = linkedMapOf<String, DistractionEvent>()
    private val shortVideo = linkedMapOf<ShortVideoBucket, ShortVideoMetrics>()
    override fun sessions() = records.values.toList()
    override fun events() = distractions.values.toList()
    override fun save(record: SessionRecord) { records[record.session.id] = record }
    override fun save(event: DistractionEvent) { distractions[event.id] = event }
    override fun shortVideoTotals() = shortVideo.toMap()
    override fun saveShortVideoTotals(totals: Map<ShortVideoBucket, ShortVideoMetrics>) { shortVideo.putAll(totals) }
}
