package com.example.waitasecond

import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Sanitized real Nord CE4 hierarchies captured 2026-09-07; no DM text is stored. */
class DeviceSnapshotTest {
    private fun snapshot(name: String): List<AccessibilityNodeSnapshot> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val doc = javaClass.getResourceAsStream("/device/$name")!!.use { factory.newDocumentBuilder().parse(it) }
        val nodes = doc.getElementsByTagName("node")
        return (0 until nodes.length).map { index ->
            val n = nodes.item(index) as Element
            fun value(key: String) = n.getAttribute(key).ifBlank { null }
            AccessibilityNodeSnapshot(value("text"), value("description"), value("id"),
                n.getAttribute("selected") == "true", n.getAttribute("depth").toInt(), value("class"),
                ancestorIds = value("ancestors")?.split('|') ?: emptyList())
        }
    }
    @Test fun actualNordDmWithSharedReelRemainsAllowed() {
        val result = InstagramReelsDetector.detect(snapshot("nord-dm.xml"))
        assertEquals(InstagramScreen.DM_CONVERSATION, result.screenType)
        assertFalse(result.isReels)
    }
    @Test fun actualNordReelsWithoutOriginalAudioIsRecognized() {
        assertTrue(InstagramReelsDetector.detect(snapshot("nord-reels.xml")).isReels)
    }
    @Test fun actualNordOwnerRowWorksWhenTitleIsNotExposed() {
        val screen = YouTubeClassifier.classify(snapshot("nord-youtube-owner.xml"))
        assertEquals(YouTubeScreen.VIDEO, screen.screenType)
        assertNull(screen.title)
        assertEquals("MrBeast", screen.channel)
        assertEquals(RelevanceStatus.IRRELEVANT, DbmsRelevanceScorer.score("", screen.channel).status)
    }
    @Test fun scrolledRecommendationsDoNotBecomeCurrentVideoTitle() {
        assertNull(YouTubeClassifier.classify(snapshot("nord-youtube-scrolled.xml")).title)
    }
}
