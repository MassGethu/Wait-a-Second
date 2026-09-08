package com.example.waitasecond

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftDetectorTest {
    @Test
    fun strongDownwardSequenceIsDrifting() {
        assertTrue(DriftDetector.analyze(events(0.9, 0.6, 0.3)).isDrifting)
        assertTrue(DriftDetector.analyze(events(0.9, 0.7, 0.4)).isDrifting)
    }

    @Test
    fun gentleDownwardSequenceAboveThresholdIsNotYetDrifting() {
        assertFalse(DriftDetector.analyze(events(0.9, 0.8, 0.7)).isDrifting)
    }

    @Test
    fun risingSequenceIsNotDrifting() {
        assertFalse(DriftDetector.analyze(events(0.8, 0.85, 0.9)).isDrifting)
    }

    @Test
    fun oneObservationIsInsufficient() {
        assertFalse(DriftDetector.analyze(events(0.5)).isDrifting)
    }

    @Test
    fun stableHighScoresRemainStable() {
        assertFalse(DriftDetector.analyze(events(0.92, 0.88, 0.95)).isDrifting)
    }

    @Test
    fun careerToGamingSequenceDrifts() {
        assertTrue(DriftDetector.analyze(events(0.95, 0.82, 0.55, 0.15)).isDrifting)
    }

    @Test
    fun repeatTitleDoesNotSupplyThreeUniqueContents() {
        val same = events(0.9, 0.5, 0.1).map { it.copy(content = "same title") }
        assertFalse(DriftDetector.analyze(same).isDrifting)
    }

    @Test
    fun reelsIsStrongDriftSignal() {
        assertTrue(DriftDetector.analyze(listOf(SessionEvent(0, "Instagram", "REELS", 0.0,
            RelevanceStatus.IRRELEVANT, "viewer", isVideo = false))).isDrifting)
    }

    private fun events(vararg scores: Double): List<SessionEvent> = scores.mapIndexed { index, score ->
        SessionEvent(
            timestampMillis = index.toLong(),
            sourceApp = PrototypePipeline.SOURCE_YOUTUBE,
            content = "content-$index",
            score = score,
            status = RelevanceStatus.UNCERTAIN,
            reason = "test"
        )
    }
}
