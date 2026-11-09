package com.example.waitasecond

import com.example.waitasecond.core.monitoring.AppContext
import com.example.waitasecond.core.monitoring.AppSection
import com.example.waitasecond.core.monitoring.ShortVideoMetrics
import com.example.waitasecond.core.monitoring.ShortVideoPage
import com.example.waitasecond.core.monitoring.ShortVideoPlatform
import com.example.waitasecond.core.monitoring.ShortVideoPlatform.REELS
import com.example.waitasecond.core.monitoring.ShortVideoPlatform.SHORTS
import com.example.waitasecond.core.monitoring.ShortVideoTracker
import com.example.waitasecond.core.monitoring.SupportedApp
import com.example.waitasecond.data.model.MemorySessionStore
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ShortVideoTrackerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 11, 8)
    private val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun viewerTimeDoesNotDoubleCountRepeatedObservationOrCheckpoints() {
        val store = MemorySessionStore()
        val tracker = ShortVideoTracker(emptyMap(), store::saveShortVideoTotals)
        tracker.observe(REELS, 1, null, 0, midnight, zone, null)
        tracker.observe(REELS, 1, null, 500, midnight + 500, zone, null)
        tracker.checkpoint(1_000)
        tracker.checkpoint(1_000)
        val live = tracker.snapshot(1_500, midnight + 1_500, zone, null)
        assertEquals(1_500L, live.episodeMillis(1_500))
        assertEquals(ShortVideoMetrics(1_500, 1, 0), live.today[REELS])
        tracker.observe(null, -1, null, 2_000, midnight + 2_000, zone, null)
        val restored = ShortVideoTracker(store.shortVideoTotals(), store::saveShortVideoTotals)
        val state = restored.snapshot(100_000, midnight + 100_000, zone, null)
        assertNull(state.activePlatform)
        assertEquals(ShortVideoMetrics(2_000, 1, 0), state.today[REELS])
        assertEquals(2_000L, state.outsideFocusTodayMillis)
    }

    @Test fun oneEpisodeSpansMidnightAndFocusChangesWithoutMisattributingTime() {
        val store = MemorySessionStore()
        val tracker = ShortVideoTracker(emptyMap(), store::saveShortVideoTotals)
        tracker.observe(SHORTS, 4, null, 0, midnight - 2_000, zone, null)
        tracker.changeSession(1_000, "focus")
        tracker.changeSession(4_000, null)
        val state = tracker.snapshot(5_000, midnight + 3_000, zone, "focus")
        assertEquals(ShortVideoMetrics(3_000, 0, 0), state.today[SHORTS])
        assertEquals(3_000L, state.session.viewerMillis)
        assertEquals(1_000L, state.outsideFocusTodayMillis)
        assertEquals(5_000L, state.episodeMillis(5_000))
        assertEquals(1, store.shortVideoTotals().filterKeys { it.date == day.minusDays(1) }.values.sumOf { it.episodes })
    }

    @Test fun indexedPageChangesAreSeparateFromEpisodesAndRepeatedEvents() {
        val tracker = ShortVideoTracker(emptyMap()) {}
        fun page(index: Int, window: Int = 1) = ShortVideoPage.from(REELS, window,
            "com.instagram.android:id/clips_viewer_view_pager", index, index)
        fun observe(time: Long, sample: ShortVideoPage?) = tracker.observe(REELS, 1, sample, time, midnight + time, zone, null)
        observe(0, page(12)) // First reported index is the baseline.
        observe(1, page(12))
        observe(2, page(13, window = 9))
        observe(3, page(13))
        observe(4, page(13))
        observe(5, page(12)) // Backward counts as a detected transition.
        assertEquals(ShortVideoMetrics(6, 1, 2), tracker.snapshot(6, midnight + 6, zone, null).today[REELS])
        assertNull(ShortVideoPage.from(REELS, 1, "message_list", 3, 3))
        assertNull(ShortVideoPage.from(REELS, 1, "clips_viewer_view_pager", 3, 4))
        assertNull(ShortVideoPlatform.from(AppContext(SupportedApp.INSTAGRAM, AppSection.DIRECT_MESSAGES)))
        assertNull(ShortVideoPlatform.from(AppContext(SupportedApp.YOUTUBE, AppSection.VIDEO)))
    }

    @Test fun wallClockAdjustmentCannotAddUnobservedTime() {
        val start = Instant.parse("2026-11-08T10:00:00Z").toEpochMilli()
        val store = MemorySessionStore()
        val tracker = ShortVideoTracker(emptyMap(), store::saveShortVideoTotals)
        tracker.observe(REELS, 1, null, 0, start, zone, null)
        tracker.rebaseClock(1_000, start + 86_400_000, zone)
        tracker.observe(null, -1, null, 2_000, start + 86_401_000, zone, null)
        assertEquals(2_000L, store.shortVideoTotals().values.sumOf { it.viewerMillis })
        assertEquals(1_000L, tracker.snapshot(2_000, start + 86_401_000, zone, null).outsideFocusTodayMillis)
    }
}
