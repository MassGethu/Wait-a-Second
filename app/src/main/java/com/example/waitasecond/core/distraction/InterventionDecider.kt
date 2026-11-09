package com.example.waitasecond.core.distraction

import com.example.waitasecond.data.model.RelevanceResult
import com.example.waitasecond.data.model.RelevanceStatus

/** Keeps the intervention rule explicit and independently testable. */
object InterventionDecider {
    fun shouldIntervene(
        hasActiveSession: Boolean,
        result: RelevanceResult
    ): Boolean = hasActiveSession && result.status == RelevanceStatus.IRRELEVANT
}
