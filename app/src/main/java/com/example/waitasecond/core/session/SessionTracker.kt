package com.example.waitasecond.core.session

import com.example.waitasecond.data.model.EventKind
import com.example.waitasecond.core.monitoring.OverlayData
import com.example.waitasecond.core.monitoring.AppContext
import com.example.waitasecond.core.distraction.RetypePolicy
import com.example.waitasecond.core.distraction.DmDwellPolicy
import com.example.waitasecond.core.distraction.CountdownPurpose
import com.example.waitasecond.core.distraction.InterventionFlow
import com.example.waitasecond.core.distraction.InterventionPhase
import com.example.waitasecond.data.model.EventOutcome
import com.example.waitasecond.core.distraction.DriftDetector
import com.example.waitasecond.core.distraction.InterventionDecider
import com.example.waitasecond.core.monitoring.AppPackages
import com.example.waitasecond.core.monitoring.AppSection
import com.example.waitasecond.core.monitoring.MonitoringPolicy
import com.example.waitasecond.core.monitoring.SupportedApp
import com.example.waitasecond.data.model.DistractionEvent
import com.example.waitasecond.data.model.DriftResult
import com.example.waitasecond.data.model.MemorySessionStore
import com.example.waitasecond.data.model.RelevanceResult
import com.example.waitasecond.data.model.RelevanceStatus
import com.example.waitasecond.data.model.RuntimeObservation
import com.example.waitasecond.data.model.SessionEvent
import com.example.waitasecond.data.model.SessionRecord
import com.example.waitasecond.data.model.SessionStatistics
import com.example.waitasecond.data.model.SessionStore
import com.example.waitasecond.data.model.TimeInterval
import com.example.waitasecond.data.model.UserChoice
import com.example.waitasecond.data.repository.StatisticsRepository


/** One owner for all session state. Pure clock-injected core supports deterministic JVM tests.
 * Android entry points run on the main thread; synchronized methods also protect snapshots.
 */
open class SessionTracker(private val clock: () -> Long, private val log: (String, String) -> Unit = { _, _ -> },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val cooldownMillis: Long = MonitoringPolicy.COOLDOWN_MS,
    private val bootCount: () -> Int = { 0 },
    private val dmDwell: DmDwellPolicy = DmDwellPolicy()
) {
    private var pendingKind = EventKind.DISTRACTION
    private val interventionFlow = InterventionFlow(clock)
    protected var store: SessionStore = MemorySessionStore()
    private val studyIntervals = mutableListOf<TimeInterval>()
    private val appIntervals = mutableListOf<TimeInterval>()
    private var lastInterventionAt: Long? = null
    private var activeEventId: String? = null
    protected open fun onStateChanged() {}
    protected open fun onSessionBoundary(elapsed: Long, sessionId: String?) {}
    private fun changed() { checkpoint(); onStateChanged() }
    @Synchronized fun attachStore(value: SessionStore) {
        store = value
        val record = store.sessions().lastOrNull() ?: return
        session = record.session
        studyIntervals.clear(); studyIntervals.addAll(record.studyIntervals)
        appIntervals.clear(); appIntervals.addAll(record.appIntervals)
        if (session!!.isActive && (record.bootCount != bootCount() || clock() < session!!.resumedAtMillis)) {
            // A monotonic clock cannot cross a reboot. Continue from the durable checkpoint.
            session = session!!.copy(accumulatedMillis = record.checkpointElapsed,
                resumedAtMillis = clock(), resumedAtEpoch = wallClock())
        }
        // A window cannot survive process death. Preserve shown attempts, dismiss unresolved choices.
        store.events().filter { it.outcome == EventOutcome.PENDING }.forEach {
            store.save(it.copy(userChoice = if (it.userChoice == UserChoice.UNKNOWN) UserChoice.AUTO_DISMISSED else it.userChoice,
                outcome = EventOutcome.INTERRUPTED, resolvedAt = wallClock()))
        }
        appTimes.clear()
        appIntervals.forEach { interval -> interval.app?.let { app ->
            appTimes[app.packageName] = (appTimes[app.packageName] ?: 0) + interval.overlap(Long.MIN_VALUE, Long.MAX_VALUE)
        } }
        expire()
    }
    @Synchronized fun currentSession(): FocusSession? { expire(); return session }
    @Synchronized fun openSession(): FocusSession? = currentSession()?.takeIf { it.isOpen }
    private fun closeStudyInterval(now: Long) {
        val current = session ?: return
        if (current.isActive) studyIntervals += TimeInterval(current.resumedAtEpoch,
            current.resumedAtEpoch + (current.elapsedMillis(now) - current.accumulatedMillis))
    }
    private fun recordSnapshot(): SessionRecord? {
        val current = session ?: return null
        val now = clock()
        val elapsed = current.elapsedMillis(now)
        val study = studyIntervals.toMutableList()
        if (current.isActive) study += TimeInterval(current.resumedAtEpoch,
            current.resumedAtEpoch + elapsed - current.accumulatedMillis)
        val apps = appIntervals.toMutableList()
        SupportedApp.fromPackage(foreground)?.let { app ->
            val duration = (now - foregroundSince).coerceAtLeast(0)
            apps += TimeInterval(wallClock() - duration, wallClock(), app)
        }
        return SessionRecord(current.copy(accumulatedMillis = elapsed, resumedAtMillis = now,
            resumedAtEpoch = current.resumedAtEpoch + elapsed - current.accumulatedMillis), study, apps, elapsed, bootCount())
    }
    @Synchronized fun checkpoint() { recordSnapshot()?.let(store::save) }
    /** A delayed alarm must not replace a session that occupied its original time slot. */
    @Synchronized fun occupiedAt(epoch: Long): Boolean {
        expire()
        return store.sessions().any { record ->
            val value = record.session
            value.startedAt <= epoch && epoch < (value.endedAt ?: (value.startedAt + value.plannedMillis))
        }
    }
    @Synchronized fun storedEvents() = store.events()
    @Synchronized fun dailyStatistics(from: Long, until: Long): SessionStatistics {
        expire()
        val current = recordSnapshot()
        val records = store.sessions().filter { it.session.id != current?.session?.id } + listOfNotNull(current)
        return StatisticsRepository.aggregate(records, store.events(), from, until)
    }
    @Synchronized fun weeklyStatistics(now: Long, zone: java.util.TimeZone): List<com.example.waitasecond.data.repository.DayStatistics> {
        expire()
        val current = recordSnapshot()
        val records = store.sessions().filter { it.session.id != current?.session?.id } + listOfNotNull(current)
        return StatisticsRepository.calendarDays(records, store.events(), now, zone)
    }
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
    private val visibleToken: String? get() = interventionFlow.state.takeIf {
        it.phase != InterventionPhase.NONE && it.phase != InterventionPhase.REQUESTED
    }?.token
    private var sequence = 0L
    private var overlayOrigin: String? = null
    private val overlay get() = interventionFlow.state.phase != InterventionPhase.NONE
    private var generation = 0L
    private var returnRequestedAt: Long? = null

    @Synchronized open fun startSession(goal: String, durationMinutes: Int, mode: FocusMode = FocusMode.LIGHT): FocusSession {
        return createSession(goal, durationMinutes, mode)
    }
    private fun createSession(goal: String, durationMinutes: Int, mode: FocusMode, scheduledRemaining: Long? = null): FocusSession {
        require(durationMinutes in 1..999)
        require(isSupportedTopic(goal.removePrefix("Study "))) { "Enter a topic between 2 and 80 characters" }
        if (session?.isOpen == true) endSession()
        generation++
        val now = clock()
        session = FocusSession(goal.trim(), durationMinutes, now, startedAt = wallClock(), mode = mode, scheduledDurationMillis = scheduledRemaining)
        dmDwell.reset(); pendingKind = EventKind.DISTRACTION
        studyIntervals.clear(); appIntervals.clear(); lastInterventionAt = null; activeEventId = null
        events.clear(); appTimes.clear(); contentTimes.clear()
        foreground = OWN_PACKAGE; foregroundSince = now
        timedTitle = null; meaningfulKey = null; episodeKey = null; pendingKey = null
        overlayOrigin = null; interventionFlow.clear(); sequence = 0
        returnRequestedAt = null
        observation = allowed("WaitaSecond", "Focus session")
        log("SESSION", "started")
        onSessionBoundary(now, session!!.id)
        changed()
        return session!!
    }

    @Synchronized fun startScheduledSession(goal: String, minutes: Int, mode: FocusMode, remaining: Long): FocusSession {
        require(remaining in 1..(minutes * 60_000L))
        check(!isSessionActive())
        return createSession(goal, minutes, mode, remaining)
    }
    private fun interventionMode() = if (pendingKind == EventKind.DM_CHECKIN) FocusMode.LIGHT else session!!.mode
    @Synchronized fun observeContext(context: AppContext) {
        if (!isSessionActive()) return
        dmDwell.observe(context.app == SupportedApp.INSTAGRAM && context.section == AppSection.DIRECT_MESSAGES && context.isDirectConversation, clock())
    }
    @Synchronized fun dmCheckinDue(): Boolean = activeSession() != null && pendingKey == null &&
        interventionFlow.state.phase == InterventionPhase.NONE && foreground == INSTAGRAM_PACKAGE && dmDwell.shouldPrompt(clock())
    @Synchronized fun pollDmCheckin(): OverlayData? {
        val current = activeSession() ?: return null
        if (!dmCheckinDue()) return null
        val context = AppContext(SupportedApp.INSTAGRAM, AppSection.DIRECT_MESSAGES, isDirectConversation = true)
        observation = allowed("Instagram", "DIRECT_MESSAGES").copy(context = context)
        pendingKind = EventKind.DM_CHECKIN
        val token = "$generation:${++sequence}"
        pendingKey = token; pendingSince = clock()
        return OverlayData(token, current.goal, context, remainingMillis(), isDmCheckin = true)
    }

    @Synchronized fun activeSession(): FocusSession? { expire(); return session?.takeIf { it.isActive } }
    fun isSessionActive() = activeSession() != null
    @Synchronized fun endSession(): FocusSession? {
        expire()
        if (session?.isOpen == true) finish(clock(), SessionStatus.CANCELLED)
        return session
    }
    private fun deadline() = session!!.resumedAtMillis + session!!.plannedMillis - session!!.accumulatedMillis
    private fun expire() {
        if (session?.isActive == true && clock() >= deadline()) finish(deadline())
        if (pendingKey != null && overlay && visibleToken == null && clock() - pendingSince >= MonitoringPolicy.LAUNCH_TIMEOUT_MS) {
            pendingKey = null; interventionFlow.clear(); overlayOrigin = null
            log("OVERLAY", "Intervention did not become visible; no attempt counted")
        }
    }
    private fun finish(now: Long, status: SessionStatus = SessionStatus.COMPLETED) {
        dmDwell.reset()
        closeIntervention()
        closeStudyInterval(now)
        flushApp(now); flushTitle(now)
        foreground = null; timedTitle = null; interventionFlow.clear(); pendingKey = null; episodeKey = null
        session = session?.copy(status = status, accumulatedMillis = session!!.elapsedMillis(now),
            endedAt = wallClock() - (clock() - now).coerceAtLeast(0))
        returnRequestedAt = null
        log("SESSION", "${status.name.lowercase()}")
        onSessionBoundary(now, null)
        changed()
    }
    @Synchronized fun remainingMillis(): Long {
        expire()
        return session?.takeIf { it.isOpen }?.remainingMillis(clock()) ?: 0
    }

    /** Root-confirmed package transitions only. Our intervention pauses timing but preserves the episode. */
    @Synchronized fun foregroundChanged(packageName: String, interventionWindow: Boolean = false) {
        expire()
        if (session?.isActive != true) return
        val now = clock()
        if (packageName == foreground && !(overlayOrigin != null && !interventionWindow && packageName == OWN_PACKAGE)) return
        if (!(interventionWindow && packageName == OWN_PACKAGE) &&
            !(packageName == OWN_PACKAGE && interventionFlow.state.phase == InterventionPhase.LIGHT_RETYPE)) {
            if (packageName != INSTAGRAM_PACKAGE) dmDwell.observe(false, now)
        }
        val previous = foreground
        flushApp(now); flushTitle(now); timedTitle = null
        foreground = packageName; foregroundSince = now
        log("MONITOR", "${appName(previous.orEmpty())} -> ${appName(packageName)}")
        if ((interventionWindow || overlay) && packageName == OWN_PACKAGE) {
            if (SupportedApp.fromPackage(previous) != null) overlayOrigin = previous
            changed(); return
        }
        val returningFromOverlay = overlayOrigin == packageName
        overlayOrigin = null
        if (returningFromOverlay) {
            if (observation?.isVideo == true) { timedTitle = observation?.content; titleSince = now }
            changed()
            return
        }
        meaningfulKey = null; episodeKey = null; pendingKey = null
        observation = allowed(appName(packageName), "Awaiting current screen")
        if (packageName != YOUTUBE_PACKAGE && packageName != INSTAGRAM_PACKAGE) {
            observe(allowed(appName(packageName), if (packageName == OWN_PACKAGE) "Focus session" else "Other app"))
        }
        changed()
    }
    private fun flushApp(now: Long) {
        foreground?.let { key ->
            val duration = (now - foregroundSince).coerceAtLeast(0)
            SupportedApp.fromPackage(key)?.let { app ->
                val end = wallClock() - (clock() - now).coerceAtLeast(0)
                appIntervals += TimeInterval(end - duration, end, app)
            }
            appTimes[key] = (appTimes[key] ?: 0) + duration

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
        if (session?.isActive != true || overlay || SupportedApp.fromName(next.sourceApp) == null || next.sourceApp != appName(foreground.orEmpty())) return null
        val key = "${next.sourceApp}:${next.content.trim().lowercase()}"
        val now = clock()
        val previousObservation = observation
        observation = next
        val nextTitle = next.content.trim().takeIf { next.isVideo }
            ?.let { title -> timedTitle?.takeIf { it.equals(title, true) } ?: title }
        if (nextTitle != timedTitle) { flushTitle(now); timedTitle = nextTitle; titleSince = now }
        // Transient incomplete trees do not re-arm an existing distraction episode.
        if (!next.isKnown) return null
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
        if (lastInterventionAt?.let { now - it < cooldownMillis } == true) return null
        pendingKind = EventKind.DISTRACTION
        val token = "$generation:${++sequence}"
        pendingKey = token; pendingSince = now
        return token
    }

    @Synchronized fun interventionRequested(token: String): Boolean {
        expire()
        if (session?.isActive != true || pendingKey != token || overlay) return false
        interventionFlow.request(token, session!!.id, interventionMode())
        if (pendingKind == EventKind.DISTRACTION) overlayOrigin = foreground
        return true
    }
    /** Count only after our window is attached successfully. */
    @Synchronized fun interventionLaunched(token: String): Boolean {
        expire()
        if (session?.isActive != true || token != pendingKey) return false
        pendingKey = null
        interventionFlow.show(token, session!!.id, interventionMode())
        if (pendingKind == EventKind.DISTRACTION) {
            if (overlayOrigin == null) overlayOrigin = foreground
            lastInterventionAt = clock()
            if (events.isNotEmpty()) events[events.lastIndex] = events.last().copy(blocked = true)
        }
        val current = session!!
        val app = SupportedApp.fromName(observation?.sourceApp) ?: return false
        val event = DistractionEvent(timestamp = wallClock(), sessionId = current.id, studyTopic = current.goal,
            app = app, section = observation?.context?.section ?: AppSection.UNKNOWN,
            category = if (pendingKind == EventKind.DM_CHECKIN) "dm_dwell" else observation?.result?.reason ?: "unknown",
            mode = current.mode, kind = pendingKind)
        if (pendingKind == EventKind.DM_CHECKIN) dmDwell.shown(clock())
        store.save(event); activeEventId = event.id
        log("EVENT", "distraction stored")
        log("OVERLAY", "shown")
        changed()
        return true
    }
    @Synchronized fun interventionLaunchFailed(token: String) {
        if (pendingKey != token) return
        if (pendingKind == EventKind.DM_CHECKIN) dmDwell.snooze(clock())
        pendingKey = null; interventionFlow.clear(); overlayOrigin = null
        log("OVERLAY", "Launch failed; no attempt counted")
    }
    @Synchronized fun interventionState() = interventionFlow.state
    @Synchronized fun countdownRemainingMillis() = interventionFlow.remainingMillis()

    /** A system dismissal is not Continue; Strict never grants episode bypass on dismissal. */
    @Synchronized fun closeIntervention(token: String? = visibleToken) {
        if (token == null || token != interventionFlow.state.token) return
        val event = currentInterventionEvent()
        if (event?.outcome == EventOutcome.PENDING) store.save(event.copy(
            userChoice = if (event.userChoice == UserChoice.UNKNOWN) UserChoice.AUTO_DISMISSED else event.userChoice,
            outcome = EventOutcome.INTERRUPTED, resolvedAt = wallClock()))
        if (session?.mode == FocusMode.STRICT && event?.kind != EventKind.DM_CHECKIN) {
            meaningfulKey = null; episodeKey = null; lastInterventionAt = null
        }
        interventionFlow.clear()
        returnRequestedAt = null
        changed()
    }
    @Synchronized fun currentInterventionEvent() = store.events().firstOrNull { it.id == activeEventId }
    private fun recordChoice(choice: UserChoice) {
        val event = currentInterventionEvent() ?: return
        store.save(event.copy(userChoice = choice, actionAt = wallClock()))
        log("OVERLAY", "action=$choice mode=${event.mode}")
    }
    @Synchronized fun chooseIntervention(token: String, choice: UserChoice): Boolean {
        expire()
        if (session?.isActive != true || token != visibleToken) return false
        if (currentInterventionEvent()?.kind == EventKind.DM_CHECKIN && interventionFlow.state.hasOverlay) {
            val outcome = when (choice) {
                UserChoice.WORK_CONFIRMED -> EventOutcome.WORK_CONFIRMED
                UserChoice.PERSONAL_CONFIRMED -> EventOutcome.PERSONAL_CONFIRMED
                else -> null
            }
            if (outcome != null) {
                recordChoice(choice); dmDwell.snooze(clock()); resolveIntervention(outcome); return true
            }
            if (choice == UserChoice.CONTINUE_ANYWAY) return false
        }
        val accepted = when (choice) {
            UserChoice.CONTINUE_ANYWAY -> interventionFlow.continueAnyway(token)
            UserChoice.GO_BACK, UserChoice.RETURN_TO_SESSION -> interventionFlow.goBack(token)
            else -> false
        }
        if (!accepted) return false
        recordChoice(choice)
        if (choice != UserChoice.CONTINUE_ANYWAY) returnRequestedAt = clock()
        changed()
        return true
    }
    @Synchronized fun remindStrictReset(token: String): Boolean {
        expire()
        if (session?.isActive != true || !interventionFlow.remindReset(token)) return false
        changed()
        return true
    }
    @Synchronized fun interventionNavigationFailed(token: String) {
        if (!interventionFlow.navigationFailed(token)) return
        returnRequestedAt = null
        changed()
    }
    @Synchronized fun cancelRetype(token: String) {
        if (interventionFlow.cancelRetype(token)) changed()
    }
    @Synchronized fun retypeSentence(token: String): String? {
        expire()
        val event = currentInterventionEvent() ?: return null
        if (session?.isActive != true || interventionFlow.state.token != token || event.kind != EventKind.DISTRACTION) return null
        return RetypePolicy.sentence(
            AppContext(event.app, event.section), session!!.goal)
    }
    @Synchronized fun completeRetype(token: String, typed: String): Boolean {
        val sentence = retypeSentence(token) ?: return false
        if (!RetypePolicy.matches(sentence, typed)) return false
        if (interventionFlow.state.token != token || interventionFlow.state.phase != InterventionPhase.LIGHT_RETYPE) return false
        resolveIntervention(EventOutcome.CONTINUED_AFTER_RETYPE)
        return true
    }
    @Synchronized fun resetVisibility(token: String, visible: Boolean) {
        interventionFlow.resetVisibility(token, visible)
        changed()
    }
    private fun resolveIntervention(outcome: EventOutcome) {
        val event = currentInterventionEvent() ?: return
        if (event.outcome != EventOutcome.PENDING) return
        store.save(event.copy(outcome = outcome, resolvedAt = wallClock(),
            returnedToFocus = outcome in setOf(EventOutcome.RETURNED_TO_FOCUS, EventOutcome.RESET_COMPLETED)))
        interventionFlow.clear()
        if (event.mode == FocusMode.STRICT && event.kind == EventKind.DISTRACTION) {
            meaningfulKey = null; episodeKey = null; lastInterventionAt = null
        }
        returnRequestedAt = null
        log("EVENT", "outcome=$outcome")
        changed()
    }
    @Synchronized fun advanceIntervention() {
        expire()
        if (session?.isActive != true) return
        if (interventionFlow.state.phase == InterventionPhase.RETURNING_TO_FOCUS &&
            returnRequestedAt?.let { clock() - it > MonitoringPolicy.COOLDOWN_MS } == true) {
            closeIntervention()
            return
        }
        when (interventionFlow.completedPurpose()) {
            CountdownPurpose.STRICT_RESET -> resolveIntervention(EventOutcome.RESET_COMPLETED)
            null -> Unit
        }
    }
    @Synchronized fun confirmReturnToFocus() {
        expire()
        if (interventionFlow.state.phase != InterventionPhase.RETURNING_TO_FOCUS) return
        val requestedAt = returnRequestedAt ?: return
        if (clock() - requestedAt > MonitoringPolicy.COOLDOWN_MS) return
        resolveIntervention(EventOutcome.RETURNED_TO_FOCUS)
    }
    @Synchronized fun isCurrentIntervention(token: String): Boolean = interventionFlow.state.hasOverlay && visibleToken == token

    /** Real current/last-session metrics; no fabricated daily history or inferred redirects. */
    @Synchronized fun statistics(): SessionStatistics {
        expire()
        val current = session
        val elapsed = if (current == null) 0L else
            current.elapsedMillis(clock())
        val durations = appDurations()
        val all = store.events().filter { it.sessionId == current?.id }
        val distractions = all.filter { it.kind == EventKind.DISTRACTION }
        val dm = all.filter { it.kind == EventKind.DM_CHECKIN }
        return SessionStatistics(current != null, elapsed, distractions.size, distractions.count(StatisticsRepository::isSuccessfulRedirect),
            distractions.groupingBy { it.app.displayName }.eachCount(),
            mapOf("YouTube" to (durations[YOUTUBE_PACKAGE] ?: 0),
                "Instagram" to (durations[INSTAGRAM_PACKAGE] ?: 0)),dm.size,dm.count { it.outcome == EventOutcome.WORK_CONFIRMED },dm.count { it.outcome == EventOutcome.RETURNED_TO_FOCUS })
    }

    @Synchronized fun isInterventionVisible(): Boolean { expire(); return interventionFlow.state.hasOverlay || interventionFlow.state.phase == InterventionPhase.REQUESTED }
    @Synchronized fun distractionAttempts(): Int { expire(); return store.events().count { it.sessionId == session?.id && it.kind == EventKind.DISTRACTION } }
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
        fun isSupportedTopic(value: String): Boolean = value.trim().length in 2..80
        const val OWN_PACKAGE = AppPackages.OWN
        const val YOUTUBE_PACKAGE = AppPackages.YOUTUBE
        const val INSTAGRAM_PACKAGE = AppPackages.INSTAGRAM
        fun appName(pkg: String) = when (pkg) {
            OWN_PACKAGE -> "WaitaSecond"; YOUTUBE_PACKAGE -> "YouTube"; INSTAGRAM_PACKAGE -> "Instagram"; else -> "Other"
        }
        fun allowed(app: String, content: String) = RuntimeObservation(app, content,
            RelevanceResult(0.5, RelevanceStatus.UNCERTAIN, "Allowed prototype screen"))
    }
}
