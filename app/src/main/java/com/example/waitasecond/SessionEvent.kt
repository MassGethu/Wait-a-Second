package com.example.waitasecond

enum class RelevanceStatus {
    RELEVANT,
    UNCERTAIN,
    IRRELEVANT
}

data class RelevanceResult(
    val score: Double,
    val status: RelevanceStatus,
    val reason: String
)

data class SessionEvent(
    val timestampMillis: Long,
    val sourceApp: String,
    val content: String,
    val score: Double,
    val status: RelevanceStatus,
    val reason: String,
    val isVideo: Boolean = true,
    val blocked: Boolean = false
)

data class DriftResult(
    val isDrifting: Boolean,
    val recentScores: List<Double>,
    val reason: String
)

data class RuntimeObservation(
    val sourceApp: String,
    val content: String,
    val result: RelevanceResult,
    val isVideo: Boolean = false,
    val isKnown: Boolean = true,
    val contentGroup: String? = null
)
