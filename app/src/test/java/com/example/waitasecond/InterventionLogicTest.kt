package com.example.waitasecond

import com.example.waitasecond.core.distraction.InterventionDecider
import com.example.waitasecond.data.model.RelevanceResult
import com.example.waitasecond.data.model.RelevanceStatus

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
