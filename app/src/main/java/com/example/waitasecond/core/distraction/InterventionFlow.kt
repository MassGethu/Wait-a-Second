package com.example.waitasecond.core.distraction

import com.example.waitasecond.core.session.FocusMode

enum class InterventionPhase { NONE, REQUESTED, INTERVENTION, LIGHT_RETYPE, RETURNING_TO_FOCUS, STRICT_FOCUS_RESET }
enum class CountdownPurpose { STRICT_RESET }

/** One monotonic countdown value for the Strict reset. Rebinding UI never restarts it. */
data class FocusCountdown(
    val purpose: CountdownPurpose,
    val remainingAtResume: Long = DURATION_MS,
    val resumedAt: Long? = null
) {
    fun remaining(now: Long) = (remainingAtResume - (resumedAt?.let { (now - it).coerceAtLeast(0) } ?: 0)).coerceAtLeast(0)
    fun resume(now: Long) = if (resumedAt == null) copy(resumedAt = now) else this
    fun pause(now: Long) = copy(remainingAtResume = remaining(now), resumedAt = null)
    companion object { const val DURATION_MS = 10_000L }
}
data class InterventionState(
    val phase: InterventionPhase = InterventionPhase.NONE,
    val token: String? = null,
    val sessionId: String? = null,
    val mode: FocusMode = FocusMode.LIGHT,
    val countdown: FocusCountdown? = null
) {
    val hasOverlay get() = phase == InterventionPhase.INTERVENTION
    val canContinue get() = mode == FocusMode.LIGHT && phase == InterventionPhase.INTERVENTION
}

/** Only this class changes intervention/countdown phase. All callers serialize through SessionTracker. */
class InterventionFlow(private val clock: () -> Long) {
    var state = InterventionState()
        private set
    fun request(token: String, sessionId: String, mode: FocusMode) {
        check(state.phase == InterventionPhase.NONE)
        state = InterventionState(InterventionPhase.REQUESTED, token, sessionId, mode)
    }
    fun show(token: String, sessionId: String, mode: FocusMode) {
        check(state.phase == InterventionPhase.NONE || (state.phase == InterventionPhase.REQUESTED && state.token == token))
        state = InterventionState(InterventionPhase.INTERVENTION, token, sessionId, mode)
    }
    fun continueAnyway(token: String): Boolean {
        if (state.token != token || !state.canContinue) return false
        state = state.copy(phase = InterventionPhase.LIGHT_RETYPE, countdown = null)
        return true
    }
    fun cancelRetype(token: String): Boolean {
        if (state.token != token || state.phase != InterventionPhase.LIGHT_RETYPE) return false
        state = state.copy(phase = InterventionPhase.INTERVENTION)
        return true
    }
    fun goBack(token: String): Boolean {
        if (state.token != token || !state.hasOverlay) return false
        state = if (state.mode == FocusMode.STRICT) state.copy(phase = InterventionPhase.STRICT_FOCUS_RESET,
            countdown = state.countdown ?: FocusCountdown(CountdownPurpose.STRICT_RESET))
        else state.copy(phase = InterventionPhase.RETURNING_TO_FOCUS, countdown = null)
        return true
    }
    fun remindReset(token: String): Boolean {
        if (state.token != token || state.phase != InterventionPhase.STRICT_FOCUS_RESET) return false
        state = state.copy(phase = InterventionPhase.INTERVENTION, countdown = state.countdown?.pause(clock()))
        return true
    }
    fun navigationFailed(token: String): Boolean {
        if (state.token != token || state.phase !in setOf(InterventionPhase.RETURNING_TO_FOCUS, InterventionPhase.STRICT_FOCUS_RESET)) return false
        state = state.copy(phase = InterventionPhase.INTERVENTION, countdown = null)
        return true
    }
    fun resetVisibility(token: String, visible: Boolean) {
        if (state.token != token || state.phase != InterventionPhase.STRICT_FOCUS_RESET) return
        state = state.copy(countdown = state.countdown?.let { if (visible) it.resume(clock()) else it.pause(clock()) })
    }
    fun completedPurpose(): CountdownPurpose? = state.countdown?.takeIf {
        it.resumedAt != null && it.remaining(clock()) == 0L
    }?.purpose
    fun remainingMillis() = state.countdown?.remaining(clock()) ?: 0L
    fun clear() { state = InterventionState() }
}
