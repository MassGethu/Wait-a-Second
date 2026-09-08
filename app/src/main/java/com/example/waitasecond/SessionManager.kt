package com.example.waitasecond

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** One owner for all session state. Pure clock-injected core supports deterministic JVM tests.
 * Android entry points run on the main thread; synchronized methods also protect snapshots.
 */
open class SessionTracker(private val clock: () -> Long, private val log: (String, String) -> Unit = { _, _ -> }) {
    private var session: FocusSession? = null
    private val events = mutableListOf<SessionEvent>()
    private val appTimes = linkedMapOf<String, Long>()
    private val contentTimes = linkedMapOf<String, Long>()
    private var foreground: String? = null
    private var foregroundSince = 0L
    private var timedTitle: String? = null
    private var titleSince = 0L
    private var observation: RuntimeObservation? = null
    private var meaningfulKey: String? = null
    private var episodeKey: String? = null
    private var pendingKey: String? = null
    private var pendingSince = 0L
    private var visibleToken: String? = null
    private var sequence = 0L
    private var overlayOrigin: String? = null
    private var overlay = false
    private var attempts = 0
    private var generation = 0L
    private var finishedAt: Long? = null
    private val attemptsByApp = linkedMapOf<String, Int>()
    private var redirects = 0
    private var redirectTarget: String? = null
    private var redirectRequestedAt = 0L

    @Synchronized open fun startSession(goal: String, durationMinutes: Int): FocusSession {
        require(durationMinutes > 0)
        require(isSupportedTopic(goal)) { "Choose a DBMS study topic" }
        generation++
        val now = clock()
        session = FocusSession(goal, durationMinutes, now, true)
        events.clear(); appTimes.clear(); contentTimes.clear()
        foreground = OWN_PACKAGE; foregroundSince = now
        timedTitle = null; meaningfulKey = null; episodeKey = null; pendingKey = null
        overlayOrigin = null; overlay = false; attempts = 0; visibleToken = null; sequence = 0
        finishedAt = null; attemptsByApp.clear(); redirects = 0; redirectTarget = null
        observation = allowed("WaitaSecond", "Focus session")
        return session!!
    }

    @Synchronized fun activeSession(): FocusSession? { expire(); return session?.takeIf { it.isActive } }
    fun isSessionActive() = activeSession() != null
    @Synchronized fun endSession(): FocusSession? {
        expire()
        if (session?.isActive == true) finish(clock())
        return session
    }
    private fun deadline() = session!!.startTimeMillis + session!!.durationMinutes * 60_000L
    private fun expire() {
        if (session?.isActive == true && clock() >= deadline()) finish(deadline())
        if (redirectTarget != null && clock() - redirectRequestedAt > 15_000) redirectTarget = null
        if (pendingKey != null && overlay && visibleToken == null && clock() - pendingSince >= 5_000) {
            pendingKey = null; overlay = false; overlayOrigin = null
            log("DistractionTracker", "Intervention did not become visible; no attempt counted")
        }
    }
    private fun finish(now: Long) {
        flushApp(now); flushTitle(now)
        foreground = null; timedTitle = null; overlay = false; pendingKey = null; episodeKey = null; visibleToken = null
        session = session?.copy(isActive = false)
        finishedAt = now
        redirectTarget = null
    }
    @Synchronized fun remainingMillis(): Long {
        expire()
        return if (session?.isActive == true) (deadline() - clock()).coerceAtLeast(0) else 0
    }

    /** Root-confirmed package transitions only. Our intervention pauses timing but preserves the episode. */
    @Synchronized fun foregroundChanged(packageName: String, interventionWindow: Boolean = false) {
        expire()
        if (session?.isActive != true) return
        val now = clock()
        if (packageName == foreground && !(overlayOrigin != null && !interventionWindow && packageName == OWN_PACKAGE)) return
        val previous = foreground
        flushApp(now); flushTitle(now); timedTitle = null
        foreground = packageName; foregroundSince = now
        log("ForegroundTracker", "$previous -> $packageName")
        if (interventionWindow && packageName == OWN_PACKAGE) return
        val returningFromOverlay = overlayOrigin == packageName
        overlayOrigin = null
        if (returningFromOverlay) {
            if (observation?.isVideo == true) { timedTitle = observation?.content; titleSince = now }
            return
        }
        meaningfulKey = null; episodeKey = null; pendingKey = null
        observation = allowed(appName(packageName), "Awaiting current screen")
        if (packageName != YOUTUBE_PACKAGE && packageName != INSTAGRAM_PACKAGE) {
            observe(allowed(appName(packageName), if (packageName == OWN_PACKAGE) "Focus session" else "Other app"))
        }
    }
    private fun flushApp(now: Long) {
        foreground?.let { key ->
            val duration = (now - foregroundSince).coerceAtLeast(0)
            appTimes[key] = (appTimes[key] ?: 0) + duration
            log("ForegroundTracker", "${appName(key)} duration +${duration / 1000.0} sec")
        }
        foregroundSince = now
    }
    private fun flushTitle(now: Long) {
        timedTitle?.let { contentTimes[it] = (contentTimes[it] ?: 0) + (now - titleSince).coerceAtLeast(0) }
        titleSince = now
    }

    /** Returns a launch token only for a distinct, confidently distracting state. */
    @Synchronized fun observe(next: RuntimeObservation): String? {
        expire()
        if (session?.isActive != true || overlay || next.sourceApp != appName(foreground.orEmpty())) return null
        val key = "${next.sourceApp}:${next.content.trim().lowercase()}"
        val now = clock()
        val previousObservation = observation
        observation = next
        val nextTitle = next.content.trim().takeIf { next.isVideo }
            ?.let { title -> timedTitle?.takeIf { it.equals(title, true) } ?: title }
        if (nextTitle != timedTitle) { flushTitle(now); timedTitle = nextTitle; titleSince = now }
        // Transient incomplete trees do not re-arm an existing distraction episode.
        if (!next.isKnown) return null
        if (redirectTarget == next.sourceApp && next.content == "HOME" &&
            next.result.status != RelevanceStatus.IRRELEVANT) {
            redirects++
            redirectTarget = null
            log("DistractionTracker", "Redirect confirmed: ${next.sourceApp} home; total=$redirects")
        }
        if (key == meaningfulKey && previousObservation?.result?.status == next.result.status) return null
        meaningfulKey = key
        val distracting = InterventionDecider.shouldIntervene(true, next.result)
        if (!distracting) episodeKey = null
        // Refining a channel-only observation into its title is not a new attempt.
        // Two distinct available titles still count as distinct content.
        val sameChannelRefinement = previousObservation?.contentGroup != null &&
            previousObservation.contentGroup == next.contentGroup &&
            previousObservation.sourceApp == next.sourceApp &&
            (!previousObservation.isVideo || !next.isVideo)
        if (distracting && episodeKey != null && sameChannelRefinement) episodeKey = key
        val newAttempt = distracting && episodeKey != key
        if (newAttempt) episodeKey = key
        events += SessionEvent(now, next.sourceApp, next.content, next.result.score,
            next.result.status, next.result.reason, next.isVideo)
        if (events.size > 50) events.removeAt(0)
        if (!newAttempt) return null
        val token = "$generation:${++sequence}:$key"
        pendingKey = token; pendingSince = now
        return token
    }

    @Synchronized fun interventionRequested(token: String): Boolean {
        expire()
        if (session?.isActive != true || pendingKey != token || overlay) return false
        overlay = true; overlayOrigin = foreground
        return true
    }
    /** Count only after InterventionActivity confirms onResume, including OEM launch rejection. */
    @Synchronized fun interventionLaunched(token: String): Boolean {
        expire()
        if (session?.isActive != true || token != pendingKey) return false
        pendingKey = null; overlay = true; visibleToken = token
        if (overlayOrigin == null) overlayOrigin = foreground
        attempts++
        observation?.sourceApp?.let { attemptsByApp[it] = (attemptsByApp[it] ?: 0) + 1 }
        if (events.isNotEmpty()) events[events.lastIndex] = events.last().copy(blocked = true)
        log("DistractionTracker", "NEW DISTRACTION ATTEMPT #$attempts reason=${observation?.content}")
        return true
    }
    @Synchronized fun interventionLaunchFailed(token: String) {
        if (pendingKey == token) { pendingKey = null; overlay = false; overlayOrigin = null; log("DistractionTracker", "Launch failed; no attempt counted") }
    }
    // Both actions retain the episode until a confirmed leave/safe state/new title.
    @Synchronized fun closeIntervention(token: String? = visibleToken) {
        if (token == visibleToken) { overlay = false; visibleToken = null }
    }
    @Synchronized fun isCurrentIntervention(token: String): Boolean = overlay && visibleToken == token

    /** Closes the owning intervention while preserving the complete focus session. */
    @Synchronized fun leaveInterventionForAppHome(token: String): Boolean {
        expire()
        if (session?.isActive != true || token != visibleToken) return false
        redirectTarget = observation?.sourceApp
        redirectRequestedAt = clock()
        overlay = false
        visibleToken = null
        return true
    }

    /** Real current/last-session metrics; no fabricated daily history or inferred redirects. */
    @Synchronized fun statistics(): SessionStatistics {
        expire()
        val current = session
        val elapsed = if (current == null) 0L else
            ((finishedAt ?: clock()) - current.startTimeMillis).coerceIn(0L, current.durationMinutes * 60_000L)
        val durations = appDurations()
        return SessionStatistics(current != null, elapsed, attempts, redirects, attemptsByApp.toMap(),
            mapOf("YouTube" to (durations[YOUTUBE_PACKAGE] ?: 0),
                "Instagram" to (durations[INSTAGRAM_PACKAGE] ?: 0)))
    }

    @Synchronized fun isInterventionVisible(): Boolean { expire(); return overlay }
    @Synchronized fun distractionAttempts(): Int { expire(); return attempts }
    @Synchronized fun latestObservation(): RuntimeObservation? { expire(); return observation }
    @Synchronized fun eventsSnapshot(): List<SessionEvent> { expire(); return events.toList() }
    @Synchronized fun appDurations(): Map<String, Long> {
        expire()
        return appTimes.toMutableMap().also { copy -> foreground?.let { copy[it] = (copy[it] ?: 0) + (clock() - foregroundSince).coerceAtLeast(0) } }
    }
    @Synchronized fun youtubeDurations(): Map<String, Long> {
        expire()
        return contentTimes.toMutableMap().also { copy -> timedTitle?.let { copy[it] = (copy[it] ?: 0) + (clock() - titleSince).coerceAtLeast(0) } }
    }
    @Synchronized fun drift(): DriftResult = DriftDetector.analyze(eventsSnapshot())

    companion object {
        fun isSupportedTopic(value: String): Boolean = value.trim().length in 2..80 &&
            DbmsRelevanceScorer.score(value).status == RelevanceStatus.RELEVANT
        const val OWN_PACKAGE = "com.example.waitasecond"
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        fun appName(pkg: String) = when (pkg) {
            OWN_PACKAGE -> "WaitaSecond"; YOUTUBE_PACKAGE -> "YouTube"; INSTAGRAM_PACKAGE -> "Instagram"; else -> "Other"
        }
        fun allowed(app: String, content: String) = RuntimeObservation(app, content,
            RelevanceResult(0.5, RelevanceStatus.UNCERTAIN, "Allowed prototype screen"))
    }
}

object SessionManager : SessionTracker(SystemClock::elapsedRealtime, { tag, message -> Log.i(tag, message) }) {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val expiryCheck = object : Runnable {
        override fun run() { if (isSessionActive()) handler.postDelayed(this, 1_000) }
    }
    override fun startSession(goal: String, durationMinutes: Int): FocusSession {
        val session = super.startSession(goal, durationMinutes)
        handler.removeCallbacks(expiryCheck)
        handler.post(expiryCheck)
        return session
    }
}
