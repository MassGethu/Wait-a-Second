package com.example.waitasecond

import com.example.waitasecond.core.distraction.RetypePolicy
import com.example.waitasecond.core.monitoring.*
import org.junit.Assert.*
import org.junit.Test

class RetypePolicyTest {
    @Test fun normalizationIgnoresCaseAndRepeatedWhitespace() {
        assertTrue(RetypePolicy.matches("I am choosing this video over my goal: Study DBMS.",
            "  i AM choosing   this video over my goal: study dbms.  "))
        assertFalse(RetypePolicy.matches("one sentence", "another sentence"))
    }
    @Test fun sentenceUsesCoarseContextAndRealGoal() {
        assertEquals("I am choosing Instagram Reels over my goal: Study DBMS.",
            RetypePolicy.sentence(AppContext(SupportedApp.INSTAGRAM, AppSection.REELS), "Study DBMS"))
        assertEquals("I am choosing YouTube Shorts over my goal: French.",
            RetypePolicy.sentence(AppContext(SupportedApp.YOUTUBE, AppSection.SHORTS), "French"))
    }
}
