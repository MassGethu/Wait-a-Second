package com.example.waitasecond

import org.junit.Assert.*
import org.junit.Test

class ScreenClassifierTest {
    private fun n(text: String? = null, id: String? = null, selected: Boolean = false,
                  ancestors: List<String> = emptyList(), editable: Boolean = false) =
        AccessibilityNodeSnapshot(text, null, id, selected, ancestorIds = ancestors, isEditable = editable)
    private val navigation = listOf(n("Reels", "clips_tab"), n("Home", selected = true))
    private val reels = listOf(n("Reels", "clips_tab", true), n(id = "clips_viewer"), n("Original audio"), n("Like"), n("Comment"))
    @Test fun dmOverridesEvenStrongReelsSignals() {
        val result = InstagramReelsDetector.detect(reels + n("Message…", "message_input", editable = true) + n("Send"))
        assertEquals(InstagramScreen.DM_CONVERSATION, result.screenType); assertFalse(result.isReels)
    }
    @Test fun dmListIsAllowed() {
        val result = InstagramReelsDetector.detect(navigation + n(id = "direct_inbox"))
        assertEquals(InstagramScreen.DM_LIST, result.screenType); assertFalse(result.isReels)
    }
    @Test fun homeAndBottomReelsLabelAreAllowed() {
        val result = InstagramReelsDetector.detect(navigation)
        assertEquals(InstagramScreen.HOME, result.screenType); assertFalse(result.isReels)
    }
    @Test fun repeatedReelsLabelsAndFeedControlsAreInsufficient() {
        assertFalse(InstagramReelsDetector.detect(navigation + n("Reels") + n("Share") + n("Comment") + n("Original audio")).isReels)
    }
    @Test fun selectedTabAloneIsInsufficient() { assertFalse(InstagramReelsDetector.detect(listOf(n("Reels", selected = true))).isReels) }
    @Test fun viewerIdAloneIsInsufficient() { assertFalse(InstagramReelsDetector.detect(listOf(n(id = "clips_viewer"))).isReels) }
    @Test fun actualReelsHasMultipleSignalsAndHighConfidence() {
        val result = InstagramReelsDetector.detect(reels)
        assertTrue(result.isReels); assertTrue(result.confidence >= 0.9); assertTrue(result.matchedSignals.size >= 3)
    }
    @Test fun selectedReelsWithAudioAndTwoControlsWorksWithoutViewerId() {
        assertTrue(InstagramReelsDetector.detect(reels.filter { it.viewId != "clips_viewer" }).isReels)
    }
    @Test fun unknownIsAllowed() { assertFalse(InstagramReelsDetector.detect(emptyList()).isReels) }
    @Test fun searchAndProfileAreAllowed() {
        assertEquals(InstagramScreen.SEARCH, InstagramReelsDetector.detect(navigation.take(1) + n(id = "search_edit_text")).screenType)
        assertEquals(InstagramScreen.PROFILE, InstagramReelsDetector.detect(navigation.take(1) + n(id = "profile_header")).screenType)
    }
    @Test fun reelsWithoutAudioStillUsesSelectedViewerAndControls() {
        assertTrue(InstagramReelsDetector.detect(listOf(n("Reels, tab, 3 of 5", selected = true),
            n("Like, 200 likes"), n("Comment, 30 comments"))).isReels)
    }
    @Test fun viewerAndControlsDoNotRequireTabSelectionOrAudio() {
        assertTrue(InstagramReelsDetector.detect(listOf(n(id = "clips_video_container"), n("Like"), n("Comment"))).isReels)
    }
    @Test fun unselectedNavigationWithFeedControlsStillAllowed() {
        assertFalse(InstagramReelsDetector.detect(listOf(n("Reels"), n("Like"), n("Comment"))).isReels)
    }
    private fun watch(title: String) = listOf(n(id = "player_view"), n(title, "title", ancestors = listOf("watch_metadata")))
    @Test fun currentDbmsTitleIsRelevantDespiteGtaRecommendations() {
        val result = YouTubeClassifier.classify(watch("DBMS Normalization") + n("GTA Gameplay", "video_title", ancestors = listOf("related_videos")) + n("Music"))
        assertEquals("DBMS Normalization", result.title)
        assertEquals(RelevanceStatus.RELEVANT, DbmsRelevanceScorer.score(result.title!!).status)
    }
    @Test fun currentGtaIsIrrelevantDespiteDbmsRecommendation() {
        val result = YouTubeClassifier.classify(watch("GTA Gameplay") + n("DBMS", "video_title", ancestors = listOf("related_videos")))
        assertEquals(RelevanceStatus.IRRELEVANT, DbmsRelevanceScorer.score(result.title!!).status)
    }
    @Test fun homeRecommendationsNeverBecomeCurrentVideo() {
        val result = YouTubeClassifier.classify(listOf(n("Home", selected = true), n("GTA", "video_title")))
        assertEquals(YouTubeScreen.HOME, result.screenType); assertNull(result.title)
    }
    @Test fun ambiguousWatchTitlesAreAllowed() {
        assertNull(YouTubeClassifier.classify(watch("DBMS") + n("GTA", "watch_title")).title)
    }
    @Test fun titleWithoutPlayerIsAllowed() { assertNull(YouTubeClassifier.classify(listOf(n("GTA", "watch_title"))).title) }
    @Test fun playerWithoutTitleDoesNotScoreScreenStrings() { assertNull(YouTubeClassifier.classify(listOf(n(id = "player_view"), n("GTA"), n("Music"))).title) }
    @Test fun navigationLabelCannotBeTitle() { assertNull(YouTubeClassifier.classify(watch("Share")).title) }
    @Test fun descriptionTitleSupportedOnlyInWatchHierarchy() {
        val result = YouTubeClassifier.classify(listOf(n(id = "player_view"), AccessibilityNodeSnapshot(null, "SQL Joins", "watch_title", false)))
        assertEquals("SQL Joins", result.title)
    }
    @Test fun expandTitleAccessibilityActionIdentifiesCurrentTitle() {
        val title = AccessibilityNodeSnapshot("A MrBeast challenge", "Expand title", null, false)
        assertEquals("A MrBeast challenge", YouTubeClassifier.classify(listOf(n(id = "watch_player"), title)).title)
    }
    @Test fun currentChannelCanIdentifyEntertainmentWithoutTitleKeywords() {
        val result = YouTubeClassifier.classify(watch("We Survived The Most Extreme Places On Earth") +
            n("MrBeast", "channel_name", ancestors = listOf("video_metadata_layout")))
        assertEquals(RelevanceStatus.IRRELEVANT, DbmsRelevanceScorer.score(result.title!!, result.channel).status)
    }
    @Test fun recommendedMrBeastChannelCannotContaminateCurrentVideo() {
        val result = YouTubeClassifier.classify(watch("Software engineering careers") +
            n("MrBeast", "channel_name", ancestors = listOf("related_videos")))
        assertNull(result.channel)
        assertEquals(RelevanceStatus.UNCERTAIN, DbmsRelevanceScorer.score(result.title!!, result.channel).status)
    }

}
