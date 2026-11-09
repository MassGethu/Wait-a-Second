package com.example.waitasecond.core.session

import java.util.UUID

enum class FocusMode { LIGHT, STRICT }

enum class SessionStatus { IDLE, ACTIVE, COMPLETED, CANCELLED }

/** Monotonic time drives durations; epoch time is used only for history/day boundaries. */
data class FocusSession(
    val goal: String,
    val durationMinutes: Int,
    val startTimeMillis: Long,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val id: String = UUID.randomUUID().toString(),
    val startedAt: Long = System.currentTimeMillis(),
    val accumulatedMillis: Long = 0,
    val resumedAtMillis: Long = startTimeMillis,
    val resumedAtEpoch: Long = startedAt,
    val mode: FocusMode = FocusMode.LIGHT,
    val endedAt: Long? = null,
    val scheduledDurationMillis: Long? = null
) {
    val isActive get() = status == SessionStatus.ACTIVE
    val isOpen get() = isActive
    val plannedMillis get() = scheduledDurationMillis ?: (durationMinutes * 60_000L)
    fun elapsedMillis(now: Long): Long = (accumulatedMillis +
        if (isActive) (now - resumedAtMillis).coerceAtLeast(0) else 0).coerceIn(0, plannedMillis)
    fun remainingMillis(now: Long) = plannedMillis - elapsedMillis(now)
}
