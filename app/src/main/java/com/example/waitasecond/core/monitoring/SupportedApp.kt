package com.example.waitasecond.core.monitoring

object AppPackages {
    const val OWN = "com.example.waitasecond"
    const val YOUTUBE = "com.google.android.youtube"
    const val INSTAGRAM = "com.instagram.android"
}
enum class SupportedApp(val displayName: String, val packageName: String) {
    YOUTUBE("YouTube", AppPackages.YOUTUBE), INSTAGRAM("Instagram", AppPackages.INSTAGRAM);
    companion object {
        fun fromPackage(value: String?) = entries.firstOrNull { it.packageName == value }
        fun fromName(value: String?) = entries.firstOrNull { it.displayName == value }
    }
}
object MonitoringPolicy {
    const val SCAN_DEBOUNCE_MS = 250L
    const val COOLDOWN_MS = 15_000L
    const val LAUNCH_TIMEOUT_MS = 5_000L
    const val CHECKPOINT_MS = 30_000L
    const val UI_TICK_MS = 1_000L
}
