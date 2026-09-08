package com.example.waitasecond

/** Process-local session; startTimeMillis is monotonic elapsed realtime, not a date. */
data class FocusSession(
    val goal: String,
    val durationMinutes: Int,
    val startTimeMillis: Long,
    val isActive: Boolean
)
