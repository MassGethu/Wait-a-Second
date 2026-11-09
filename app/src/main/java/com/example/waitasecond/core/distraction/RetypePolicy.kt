package com.example.waitasecond.core.distraction

import com.example.waitasecond.core.monitoring.AppContext
import com.example.waitasecond.core.monitoring.AppSection

object RetypePolicy {
    fun normalize(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase()
    fun matches(expected: String, actual: String) = normalize(expected) == normalize(actual)
    fun sentence(context: AppContext, goal: String): String {
        val target = when (context.section) {
            AppSection.REELS -> "Instagram Reels"
            AppSection.SHORTS -> "YouTube Shorts"
            else -> "this video"
        }
        return "I am choosing $target over my goal: $goal."
    }
}
