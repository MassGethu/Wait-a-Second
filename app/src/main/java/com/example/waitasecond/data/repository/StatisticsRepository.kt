package com.example.waitasecond.data.repository

import com.example.waitasecond.core.monitoring.SupportedApp
import com.example.waitasecond.core.session.FocusMode
import com.example.waitasecond.data.model.EventKind
import com.example.waitasecond.data.model.EventOutcome
import java.util.Calendar
import java.util.TimeZone
import com.example.waitasecond.data.model.DistractionEvent
import com.example.waitasecond.data.model.SessionRecord
import com.example.waitasecond.data.model.SessionStatistics

data class DayStatistics(
    val start: Long,
    val end: Long,
    val statistics: SessionStatistics,
    val sessionsByMode: Map<FocusMode, Int>
)

object StatisticsRepository {
    fun isSuccessfulRedirect(event: DistractionEvent): Boolean = event.kind == EventKind.DISTRACTION && when (event.mode) {
        FocusMode.LIGHT -> event.outcome == EventOutcome.RETURNED_TO_FOCUS
        FocusMode.STRICT -> event.outcome == EventOutcome.RESET_COMPLETED
    }
    /** Today plus seven previous calendar days; boundaries use the requested zone, including DST. */
    fun calendarDays(records: List<SessionRecord>, events: List<DistractionEvent>, now: Long,
        zone: TimeZone = TimeZone.getDefault()): List<DayStatistics> {
        val day = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -7)
        }
        return (0..7).map {
            val start = day.timeInMillis
            day.add(Calendar.DAY_OF_MONTH, 1)
            val end = day.timeInMillis
            DayStatistics(start, end, aggregate(records, events, start, end),
                FocusMode.entries.associateWith { mode -> records.count { record ->
                    record.session.mode == mode && record.session.startedAt >= start && record.session.startedAt < end
                } })
        }
    }
    fun combine(days: List<DayStatistics>): SessionStatistics = SessionStatistics(
        days.any { it.statistics.hasSession }, days.sumOf { it.statistics.studyMillis },
        days.sumOf { it.statistics.attempts }, days.sumOf { it.statistics.redirects },
        SupportedApp.entries.associate { app -> app.displayName to days.sumOf { it.statistics.attemptsByApp[app.displayName] ?: 0 } },
        SupportedApp.entries.associate { app -> app.displayName to days.sumOf { it.statistics.monitoredAppMillis[app.displayName] ?: 0 } },
        days.sumOf { it.statistics.dmCheckins }, days.sumOf { it.statistics.dmWorkConfirmed }, days.sumOf { it.statistics.dmReturned }
    )
    fun aggregate(records: List<SessionRecord>, events: List<DistractionEvent>, from: Long, until: Long): SessionStatistics {
        require(until >= from)
        val all = events.filter { it.timestamp >= from && it.timestamp < until }
        val today = all.filter { it.kind == EventKind.DISTRACTION }
        val dm = all.filter { it.kind == EventKind.DM_CHECKIN }
        return SessionStatistics(records.isNotEmpty(),
            records.sumOf { record -> record.studyIntervals.sumOf { it.overlap(from, until) } },
            today.size, today.count(::isSuccessfulRedirect),
            SupportedApp.entries.associate { app -> app.displayName to today.count { it.app == app } },
            SupportedApp.entries.associate { app -> app.displayName to records.sumOf { record ->
                record.appIntervals.filter { it.app == app }.sumOf { it.overlap(from, until) }
            } }, dm.size, dm.count { it.outcome == EventOutcome.WORK_CONFIRMED },
            dm.count { it.outcome == EventOutcome.RETURNED_TO_FOCUS })
    }
}
