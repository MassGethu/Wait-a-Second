package com.example.waitasecond

/** Keeps the intervention rule explicit and independently testable. */
object InterventionDecider {
    fun shouldIntervene(
        hasActiveSession: Boolean,
        result: RelevanceResult
    ): Boolean = hasActiveSession && result.status == RelevanceStatus.IRRELEVANT
}
