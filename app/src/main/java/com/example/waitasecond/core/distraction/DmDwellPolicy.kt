package com.example.waitasecond.core.distraction

/** Monotonic, conversation-only dwell. No chat identity or message content is required. */
class DmDwellPolicy(
    private val threshold: Long = ENTRY_DELAY_MS,
    private val grace: Long = EXIT_GRACE_MS,
    private val cooldown: Long = COOLDOWN_MS
) {
    private var leftAt: Long? = null
    private var dueAt: Long? = null
    private var inConversation = false
    private var prompted = false
    private var cooldownUntil: Long? = null
    fun reset() { resetVisit(); cooldownUntil = null }
    private fun resetVisit() { leftAt = null; dueAt = null; inConversation = false; prompted = false }
    fun observe(conversation: Boolean, now: Long) {
        if (leftAt?.let { now - it >= grace } == true) resetVisit()
        inConversation = conversation
        if (conversation) {
            if (dueAt == null) dueAt = now + threshold
            leftAt = null
        } else if (dueAt != null && leftAt == null) leftAt = now
    }
    fun shouldPrompt(now: Long): Boolean {
        return inConversation && !prompted && dueAt?.let { now >= it } == true &&
            cooldownUntil?.let { now < it } != true
    }
    fun shown(now: Long) { prompted = true; cooldownUntil = now + cooldown }
    fun snooze(now: Long) { prompted = false; cooldownUntil = now + cooldown }
    companion object {
        const val ENTRY_DELAY_MS = 5_000L
        const val EXIT_GRACE_MS = 30_000L
        const val COOLDOWN_MS = 10 * 60_000L
        const val PROMPT_VISIBLE_MS = 20_000L
    }
}
