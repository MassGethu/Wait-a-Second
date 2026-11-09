package com.example.waitasecond

import com.example.waitasecond.core.distraction.DbmsRelevanceScorer
import com.example.waitasecond.data.model.RelevanceStatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DbmsRelevanceScorerTest {
    @Test
    fun dbmsExamplesAreRelevant() {
        listOf(
            "DBMS Normalization Tutorial",
            "SQL Joins Explained",
            "BCNF in DBMS"
        ).forEach { content ->
            val result = DbmsRelevanceScorer.score(content)
            assertEquals(content, RelevanceStatus.RELEVANT, result.status)
            assertTrue(content, result.score >= 0.7)
        }
    }

    @Test
    fun clearDistractionsAreIrrelevant() {
        listOf("GTA V Gameplay", "Funny Gaming Moments").forEach { content ->
            val result = DbmsRelevanceScorer.score(content)
            assertEquals(content, RelevanceStatus.IRRELEVANT, result.status)
            assertTrue(content, result.score <= 0.2)
        }
    }

    @Test
    fun unknownContentIsUncertainRatherThanIrrelevant() {
        listOf(
            "Software Engineering Career Advice",
            "A completely random topic"
        ).forEach { content ->
            assertEquals(
                content,
                RelevanceStatus.UNCERTAIN,
                DbmsRelevanceScorer.score(content).status
            )
        }
    }
}
