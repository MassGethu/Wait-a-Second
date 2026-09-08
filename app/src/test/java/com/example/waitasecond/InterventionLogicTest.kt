package com.example.waitasecond

import org.junit.Assert.*
import org.junit.Test

class InterventionLogicTest {
    @Test fun onlyActiveIrrelevantResultRequestsIntervention() {
        assertFalse(InterventionDecider.shouldIntervene(true, RelevanceResult(0.9, RelevanceStatus.RELEVANT, "test")))
        assertFalse(InterventionDecider.shouldIntervene(true, RelevanceResult(0.5, RelevanceStatus.UNCERTAIN, "test")))
        assertTrue(InterventionDecider.shouldIntervene(true, RelevanceResult(0.1, RelevanceStatus.IRRELEVANT, "test")))
        assertFalse(InterventionDecider.shouldIntervene(false, RelevanceResult(0.1, RelevanceStatus.IRRELEVANT, "test")))
    }
}
