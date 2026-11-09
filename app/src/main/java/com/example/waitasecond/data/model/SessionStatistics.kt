package com.example.waitasecond.data.model

data class SessionStatistics(
    val hasSession: Boolean,
    val studyMillis: Long,
    val attempts: Int,
    val redirects: Int,
    val attemptsByApp: Map<String, Int>,
    val monitoredAppMillis: Map<String, Long>,
    val dmCheckins: Int = 0, val dmWorkConfirmed: Int = 0, val dmReturned: Int = 0
) {
    val screenMillis: Long get() = monitoredAppMillis.values.sum()
    fun percentage(app: String): Int = if (attempts == 0) 0 else
        kotlin.math.round(100.0 * (attemptsByApp[app] ?: 0) / attempts).toInt()
}
