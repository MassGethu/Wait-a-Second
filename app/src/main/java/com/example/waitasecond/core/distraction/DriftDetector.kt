package com.example.waitasecond.core.distraction

import com.example.waitasecond.data.model.DriftResult
import com.example.waitasecond.data.model.RelevanceStatus
import com.example.waitasecond.data.model.SessionEvent

object DriftDetector {
    private const val MINIMUM_DROP = 0.1
    private const val FLOATING_POINT_TOLERANCE = 0.000_001

    fun analyze(events: List<SessionEvent>): DriftResult {
        if (events.lastOrNull()?.let { it.sourceApp == "Instagram" && it.status == RelevanceStatus.IRRELEVANT } == true) {
            return DriftResult(true, listOf(0.0), "Instagram Reels is a strong distraction event")
        }
        val recentScores = events
            .filter { it.sourceApp == "YouTube" && it.isVideo }
            .asReversed()
            .distinctBy { it.content.trim().lowercase() }
            .take(3)
            .asReversed()
            .map { it.score }

        if (recentScores.size < 3) {
            return DriftResult(
                isDrifting = false,
                recentScores = recentScores,
                reason = "Need three YouTube observations"
            )
        }

        val consistentlyFalling = recentScores.zipWithNext().all { (first, second) ->
            first - second + FLOATING_POINT_TOLERANCE >= MINIMUM_DROP
        }
        val isDrifting = consistentlyFalling && recentScores.last() < 0.6
        val reason = if (isDrifting) {
            "Recent YouTube relevance scores are consistently falling"
        } else {
            "Recent YouTube scores do not show a strong downward trend"
        }

        return DriftResult(isDrifting, recentScores, reason)
    }
}
