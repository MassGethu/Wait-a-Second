package com.example.waitasecond

import com.example.waitasecond.core.distraction.DbmsRelevanceScorer
import com.example.waitasecond.core.monitoring.AppSection
import com.example.waitasecond.core.monitoring.ShortVideoPlatform
import com.example.waitasecond.core.monitoring.SupportedApp
import com.example.waitasecond.data.model.RelevanceStatus
import com.example.waitasecond.service.accessibility.AccessibilityNodeSnapshot
import com.example.waitasecond.service.accessibility.ContextExtractor
import com.example.waitasecond.service.accessibility.InstagramReelsDetector
import com.example.waitasecond.service.accessibility.InstagramScreen
import com.example.waitasecond.service.accessibility.YouTubeClassifier
import com.example.waitasecond.service.accessibility.YouTubeScreen

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
    @Test fun capturedInstagramScreensSeparateChatFromViewerWithoutLabels() {
        val dm = ContextExtractor.extract(SupportedApp.INSTAGRAM, structuralSnapshot("nord-dm.xml"))
        assertEquals(AppSection.DIRECT_MESSAGES, dm.section)
        assertTrue(dm.isDirectConversation)
        assertNull(ShortVideoPlatform.from(dm))

        val reels = ContextExtractor.extract(SupportedApp.INSTAGRAM, structuralSnapshot("nord-reels.xml"))
        assertEquals(ShortVideoPlatform.REELS, ShortVideoPlatform.from(reels))
    }
    @Test fun capturedYouTubeWatchScreensDoNotCountAsShortsWithoutLabels() {
        for (file in listOf("nord-youtube-owner.xml", "nord-youtube-scrolled.xml")) {
            val context = ContextExtractor.extract(SupportedApp.YOUTUBE, structuralSnapshot(file))
            assertNull(file, ShortVideoPlatform.from(context))
        }
    }
    private fun structuralSnapshot(name: String) = snapshot(name).map {
        it.copy(text = null, contentDescription = null)
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
