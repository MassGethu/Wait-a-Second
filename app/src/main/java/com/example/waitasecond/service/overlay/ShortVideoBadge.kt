package com.example.waitasecond.service.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.example.waitasecond.R
import com.example.waitasecond.core.monitoring.MonitoringPolicy
import com.example.waitasecond.data.repository.ShortVideoUiState
import com.example.waitasecond.ui.components.FocusFormat

/** An optional, touch-through label. Its UI ticker runs only while the label is attached. */
class ShortVideoBadge(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var label: TextView? = null
    private var episodeStart: Long? = null
    private val tick = object : Runnable {
        override fun run() {
            val view = label ?: return
            val start = episodeStart ?: return
            val seconds = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0) / 1_000
            view.text = service.getString(R.string.short_video_badge, FocusFormat.timer(seconds * 1_000))
            handler.postDelayed(this, MonitoringPolicy.UI_TICK_MS)
        }
    }

    fun render(state: ShortVideoUiState) {
        val start = state.metrics.episodeStartedAtElapsed.takeIf { state.enabled && state.badgeEnabled }
        if (start == episodeStart) return
        clear()
        if (start == null) return
        episodeStart = start // A failed attachment is retried only for a new episode.
        val theme = ContextThemeWrapper(service, R.style.Theme_WaitASecond)
        val view = LayoutInflater.from(theme).inflate(R.layout.overlay_short_video_badge, FrameLayout(theme), false) as TextView
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = service.resources.getDimensionPixelSize(R.dimen.short_video_badge_offset)
        }
        try {
            windows.addView(view, params)
            label = view
            tick.run()
        } catch (_: RuntimeException) {
            // Window access can disappear with the accessibility connection.
        }
    }

    fun clear() {
        handler.removeCallbacks(tick)
        episodeStart = null
        label?.let { try { windows.removeViewImmediate(it) } catch (_: IllegalArgumentException) { } }
        label = null
    }
}
