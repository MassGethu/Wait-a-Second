package com.example.waitasecond.core.monitoring

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ShortVideoPlatform(val app: SupportedApp) {
    REELS(SupportedApp.INSTAGRAM), SHORTS(SupportedApp.YOUTUBE);

    companion object {
        fun from(context: AppContext?) = when {
            context?.app == SupportedApp.INSTAGRAM && context.section == AppSection.REELS -> REELS
            context?.app == SupportedApp.YOUTUBE && context.section == AppSection.SHORTS -> SHORTS
            else -> null
        }
    }
}

data class ShortVideoBucket(val date: LocalDate, val platform: ShortVideoPlatform, val sessionId: String?)

data class ShortVideoMetrics(val viewerMillis: Long = 0, val episodes: Int = 0, val transitions: Int = 0) {
    operator fun plus(other: ShortVideoMetrics) = ShortVideoMetrics(
        viewerMillis + other.viewerMillis, episodes + other.episodes, transitions + other.transitions
    )
}

/** Only an indexed page in a recognized full-screen pager can contribute a transition. */
data class ShortVideoPage(val platform: ShortVideoPlatform, val windowId: Int, val pagerId: String, val index: Int) {
    companion object {
        fun from(platform: ShortVideoPlatform, windowId: Int, sourceId: String?, first: Int, last: Int): ShortVideoPage? {
            if (windowId < 0 || first < 0 || first != last) return null
            val id = sourceId?.substringAfterLast('/') ?: return null
            val isPager = when (platform) {
                ShortVideoPlatform.REELS -> id == "clips_viewer_view_pager"
                ShortVideoPlatform.SHORTS -> id in shortsPagers
            }
            return if (isPager) ShortVideoPage(platform, windowId, id, first) else null
        }
        private val shortsPagers = setOf("reel_watch_pager", "reel_player_view_pager", "shorts_view_pager", "reel_recycler")
    }
}

data class ShortVideoState(
    val activePlatform: ShortVideoPlatform? = null,
    val episodeStartedAtElapsed: Long? = null,
    val today: Map<ShortVideoPlatform, ShortVideoMetrics> = emptyMap(),
    val outsideFocusTodayMillis: Long = 0,
    val sessionId: String? = null,
    val session: ShortVideoMetrics = ShortVideoMetrics()
) {
    fun episodeMillis(elapsed: Long) = episodeStartedAtElapsed?.let { (elapsed - it).coerceAtLeast(0) } ?: 0
}

/** Main-thread state. Persists absolute aggregate rows, so replaying a write never adds time twice. */
class ShortVideoTracker(
    initial: Map<ShortVideoBucket, ShortVideoMetrics>,
    private val persist: (Map<ShortVideoBucket, ShortVideoMetrics>) -> Unit
) {
    private data class Episode(
        val platform: ShortVideoPlatform,
        val windowId: Int,
        val startedAt: Long,
        var sessionId: String?,
        var elapsed: Long,
        var epoch: Long,
        var zone: ZoneId,
        var page: ShortVideoPage? = null
    )

    private val days = initial.entries.groupBy({ it.key.date }, { it.toPair() })
        .mapValuesTo(mutableMapOf()) { (_, entries) -> entries.toMap(mutableMapOf()) }
    private val sessions = mutableMapOf<String, ShortVideoMetrics>().apply {
        initial.forEach { (bucket, metrics) -> bucket.sessionId?.let { put(it, (get(it) ?: ShortVideoMetrics()) + metrics) } }
    }
    private var active: Episode? = null

    fun observe(platform: ShortVideoPlatform?, windowId: Int, page: ShortVideoPage?,
                elapsed: Long, epoch: Long, zone: ZoneId, sessionId: String?): Boolean {
        val previous = active
        val changed = mutableMapOf<ShortVideoBucket, ShortVideoMetrics>()
        if (previous != null && (previous.platform != platform || previous.windowId != windowId)) {
            accrue(previous, elapsed, changed)
            active = null
        }
        if (platform != null && active == null) {
            active = Episode(platform, windowId, elapsed, sessionId, elapsed, epoch, zone)
            add(changed, ShortVideoBucket(date(epoch, zone), platform, sessionId), ShortVideoMetrics(episodes = 1))
        }
        active?.let { episode ->
            if (page != null && page.platform == platform && page.windowId == windowId) {
                val last = episode.page
                if (last != null && page.pagerId == last.pagerId && page.index != last.index) {
                    val transitionEpoch = episode.epoch + (elapsed - episode.elapsed).coerceAtLeast(0)
                    add(changed, ShortVideoBucket(date(transitionEpoch, episode.zone), episode.platform, sessionId),
                        ShortVideoMetrics(transitions = 1))
                }
                episode.page = page
            }
        }
        save(changed)
        return previous !== active || changed.isNotEmpty()
    }

    fun checkpoint(elapsed: Long) {
        val episode = active ?: return
        val changed = mutableMapOf<ShortVideoBucket, ShortVideoMetrics>()
        accrue(episode, elapsed, changed)
        save(changed)
    }

    fun changeSession(elapsed: Long, sessionId: String?) {
        val episode = active ?: return
        if (episode.sessionId == sessionId) return
        checkpoint(elapsed)
        episode.sessionId = sessionId
    }

    fun rebaseClock(elapsed: Long, epoch: Long, zone: ZoneId) {
        checkpoint(elapsed)
        active?.let { it.epoch = epoch; it.zone = zone }
    }

    fun snapshot(elapsed: Long, epoch: Long, zone: ZoneId, sessionId: String?): ShortVideoState {
        val today = date(epoch, zone)
        val byPlatform = mutableMapOf<ShortVideoPlatform, ShortVideoMetrics>()
        var session = sessions[sessionId] ?: ShortVideoMetrics()
        var outside = 0L
        fun include(bucket: ShortVideoBucket, metrics: ShortVideoMetrics) {
            if (bucket.date == today) {
                byPlatform[bucket.platform] = (byPlatform[bucket.platform] ?: ShortVideoMetrics()) + metrics
                if (bucket.sessionId == null) outside += metrics.viewerMillis
            }
            if (sessionId != null && bucket.sessionId == sessionId) session += metrics
        }
        days[today]?.forEach { (bucket, metrics) ->
            byPlatform[bucket.platform] = (byPlatform[bucket.platform] ?: ShortVideoMetrics()) + metrics
            if (bucket.sessionId == null) outside += metrics.viewerMillis
        }
        val episode = active
        if (episode != null) split(episode, elapsed, ::include)
        return ShortVideoState(episode?.platform, episode?.startedAt, byPlatform.toMap(), outside, sessionId, session)
    }

    private fun accrue(episode: Episode, elapsed: Long, changed: MutableMap<ShortVideoBucket, ShortVideoMetrics>) {
        split(episode, elapsed) { bucket, metrics -> add(changed, bucket, metrics) }
        episode.epoch += (elapsed - episode.elapsed).coerceAtLeast(0)
        episode.elapsed = elapsed.coerceAtLeast(episode.elapsed)
    }

    private fun split(episode: Episode, elapsed: Long, add: (ShortVideoBucket, ShortVideoMetrics) -> Unit) {
        var cursor = episode.epoch
        val end = cursor + (elapsed - episode.elapsed).coerceAtLeast(0)
        while (cursor < end) {
            val day = date(cursor, episode.zone)
            val nextDay = day.plusDays(1).atStartOfDay(episode.zone).toInstant().toEpochMilli()
            val until = minOf(end, nextDay)
            add(ShortVideoBucket(day, episode.platform, episode.sessionId), ShortVideoMetrics(viewerMillis = until - cursor))
            cursor = until
        }
    }

    private fun add(changed: MutableMap<ShortVideoBucket, ShortVideoMetrics>, bucket: ShortVideoBucket, delta: ShortVideoMetrics) {
        val totals = days.getOrPut(bucket.date) { mutableMapOf() }
        val next = (totals[bucket] ?: ShortVideoMetrics()) + delta
        totals[bucket] = next
        bucket.sessionId?.let { sessions[it] = (sessions[it] ?: ShortVideoMetrics()) + delta }
        changed[bucket] = next
    }

    private fun save(changed: Map<ShortVideoBucket, ShortVideoMetrics>) {
        if (changed.isNotEmpty()) persist(changed)
    }

    private fun date(epoch: Long, zone: ZoneId) = Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate()
}
