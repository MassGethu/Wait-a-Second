package com.example.waitasecond.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.core.content.edit
import com.example.waitasecond.core.monitoring.AppContext
import com.example.waitasecond.core.monitoring.AppSection
import com.example.waitasecond.core.monitoring.MonitoringPolicy
import com.example.waitasecond.core.monitoring.ShortVideoPage
import com.example.waitasecond.core.monitoring.ShortVideoPlatform
import com.example.waitasecond.core.monitoring.ShortVideoState
import com.example.waitasecond.core.monitoring.ShortVideoTracker
import com.example.waitasecond.core.monitoring.SupportedApp
import com.example.waitasecond.data.model.SessionStore
import java.time.ZoneId

data class DetectedActivity(val app: SupportedApp, val section: AppSection)

data class ShortVideoUiState(
    val ready: Boolean = false,
    val enabled: Boolean = false,
    val badgeEnabled: Boolean = false,
    val activity: DetectedActivity? = null,
    val metrics: ShortVideoState = ShortVideoState()
)

/** Application-owned monitoring snapshot. Reads and updates run on the main thread. */
object ShortVideoRepository {
    private const val OPT_IN = "short_video_tracking"
    private const val LIVE_BADGE = "short_video_badge"
    private lateinit var preferences: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableLiveData(ShortVideoUiState())
    val state: LiveData<ShortVideoUiState> get() = mutableState
    private var tracker: ShortVideoTracker? = null
    private var sessionId: String? = null
    private var activity: DetectedActivity? = null
    private var viewerActive = false
    private val checkpoint = object : Runnable {
        override fun run() {
            SessionManager.activeSession() // Close an expired focus bucket at its deadline before saving time.
            tracker?.checkpoint(SystemClock.elapsedRealtime())
            publish()
            if (viewerActive) handler.postDelayed(this, MonitoringPolicy.CHECKPOINT_MS)
        }
    }

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("focus_options", Context.MODE_PRIVATE)
    }

    fun attachStore(store: SessionStore, currentSessionId: String?) {
        tracker = ShortVideoTracker(store.shortVideoTotals(), store::saveShortVideoTotals)
        sessionId = currentSessionId
        publish()
    }

    fun enabled() = preferences.getBoolean(OPT_IN, false)

    fun setEnabled(enabled: Boolean) {
        if (enabled == enabled()) return
        preferences.edit { putBoolean(OPT_IN, enabled) }
        if (!enabled) observe(null)
        publish()
    }

    fun setBadgeEnabled(enabled: Boolean) {
        if (enabled == preferences.getBoolean(LIVE_BADGE, false)) return
        preferences.edit { putBoolean(LIVE_BADGE, enabled) }
        publish()
    }

    fun observe(context: AppContext?, windowId: Int = -1, page: ShortVideoPage? = null) {
        val next = context?.let { DetectedActivity(it.app, it.section) }
        val platform = if (enabled()) ShortVideoPlatform.from(context) else null
        val changed = tracker?.observe(platform, windowId, page, SystemClock.elapsedRealtime(),
            System.currentTimeMillis(), ZoneId.systemDefault(), sessionId) == true
        if (viewerActive != (platform != null && tracker != null)) {
            viewerActive = platform != null && tracker != null
            handler.removeCallbacks(checkpoint)
            if (viewerActive) handler.postDelayed(checkpoint, MonitoringPolicy.CHECKPOINT_MS)
        }
        val activityChanged = next != activity
        activity = next
        if (changed || activityChanged) publish()
    }

    fun sessionChanged(elapsed: Long, id: String?) {
        sessionId = id
        tracker?.changeSession(elapsed, id)
        publish()
    }

    fun clockChanged() {
        tracker?.rebaseClock(SystemClock.elapsedRealtime(), System.currentTimeMillis(), ZoneId.systemDefault())
        publish()
    }

    fun snapshot(): ShortVideoUiState = ShortVideoUiState(tracker != null, enabled(), preferences.getBoolean(LIVE_BADGE, false), activity,
        tracker?.snapshot(SystemClock.elapsedRealtime(), System.currentTimeMillis(), ZoneId.systemDefault(), sessionId)
            ?: ShortVideoState())

    private fun publish() { mutableState.value = snapshot() }
}
