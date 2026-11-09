package com.example.waitasecond.core.planning

import com.example.waitasecond.core.session.FocusMode
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class FocusPreset(val id: String = UUID.randomUUID().toString(), val name: String,
    val goal: String, val durationMinutes: Int, val mode: FocusMode) {
    init { require(name.trim().length in 1..80); require(goal.trim().length in 2..80); require(durationMinutes in 1..999) }
    companion object {
        val defaults get() = listOf(FocusPreset("default-dbms", "Study DBMS", "Study DBMS", 25, FocusMode.LIGHT),
            FocusPreset("default-deep", "Deep Work", "Deep Work", 60, FocusMode.STRICT))
    }
}
/** ISO weekdays: Monday=1 through Sunday=7. Times are always local, never a fixed UTC offset. */
data class FocusSchedule(val id: String = UUID.randomUUID().toString(), val presetId: String,
    val days: Set<Int>, val hour: Int, val minute: Int, val enabled: Boolean = true,
    val nextFireAt: Long? = null, val lastHandledAt: Long? = null) {
    init { require(days.isNotEmpty() && days.all { it in 1..7 }); require(hour in 0..23 && minute in 0..59) }
}
data class ScheduleRun(val id: String, val scheduleId: String, val fireAt: Long, val result: String, val sessionId: String? = null)
object SchedulePolicy {
    fun nextFire(schedule: FocusSchedule, after: Long, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        return (0L..7L).map { date.plusDays(it) }.filter { it.dayOfWeek.value in schedule.days }
            .map { it.atTime(LocalTime.of(schedule.hour, schedule.minute)).atZone(zone).toInstant().toEpochMilli() }
            .first { it > after }
        // atZone moves nonexistent spring times forward and chooses the first fall overlap.
    }
    fun remaining(durationMinutes: Int, fireAt: Long, now: Long, active: Boolean): Long =
        if (active || now < fireAt) 0 else (durationMinutes * 60_000L - (now - fireAt)).coerceAtLeast(0)
}
