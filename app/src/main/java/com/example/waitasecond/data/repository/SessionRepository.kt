package com.example.waitasecond.data.repository

import com.example.waitasecond.core.planning.*
import com.example.waitasecond.service.scheduling.FocusScheduler
import com.example.waitasecond.BuildConfig
import com.example.waitasecond.core.monitoring.MonitoringPolicy
import com.example.waitasecond.core.session.FocusSession
import com.example.waitasecond.core.session.FocusMode
import com.example.waitasecond.core.distraction.InterventionState
import com.example.waitasecond.core.session.SessionTracker
import com.example.waitasecond.data.local.LocalSessionStore
import com.example.waitasecond.data.model.DistractionEvent
import com.example.waitasecond.data.model.EventKind
import com.example.waitasecond.data.model.SessionStatistics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.Calendar
import java.util.concurrent.Executors

object FocusLog {
    fun write(tag: String, message: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("WAS/$tag", message)
    }
}
data class FocusUiState(
    val session: FocusSession? = null,
    val remainingMillis: Long = 0,
    val statistics: SessionStatistics = SessionStatistics(false, 0, 0, 0, emptyMap(), emptyMap()),
    val events: List<DistractionEvent> = emptyList(),
    val loading: Boolean = true,
    val storageError: Boolean = false,
    val intervention: InterventionState = InterventionState(),
    val countdownMillis: Long = 0,
    val days: List<DayStatistics> = emptyList(),
    val presets: List<FocusPreset> = emptyList(), val schedules: List<FocusSchedule> = emptyList(),
    val scheduleRuns: List<ScheduleRun> = emptyList(),
    val mindfulPromptVisible: Boolean = false
)
/** Application owner. One ticker publishes derived values; it never decrements session time. */
private object DeviceBoot { var count = 0 }

object SessionManager : SessionTracker(SystemClock::elapsedRealtime, FocusLog::write,
    bootCount = { DeviceBoot.count }) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val mutableState = MutableLiveData(FocusUiState())
    val state: LiveData<FocusUiState> get() = mutableState
    private var ready = false
    private lateinit var appContext: Context
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private var storageError = false
    private var lastCheckpoint = 0L
    private val refresh = object : Runnable {
        override fun run() {
            if (!ready) return
            publish()
            if (isSessionActive()) {
                if (SystemClock.elapsedRealtime() - lastCheckpoint >= MonitoringPolicy.CHECKPOINT_MS) {
                    checkpoint(); lastCheckpoint = SystemClock.elapsedRealtime()
                }
                handler.removeCallbacks(this)
                handler.postDelayed(this, MonitoringPolicy.UI_TICK_MS)
            }
        }
    }
    fun initialize(context: Context) {
        appContext = context.applicationContext
        ShortVideoRepository.initialize(appContext)
        DeviceBoot.count = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
        worker.execute {
            val store = LocalSessionStore(context, worker, onFailure = {
                FocusLog.write("STORAGE", "Local persistence unavailable")
                handler.post { storageError = true; if (ready) publish() }
            })
            try { store.load() } catch (_: Exception) { handler.post { storageError = true } }
            handler.post {
                attachStore(store)
                ShortVideoRepository.attachStore(store, activeSession()?.id)
                ready = true
                onStateChanged()
                readyCallbacks.toList().also { readyCallbacks.clear() }.forEach { it() }
                FocusScheduler.reschedule(appContext)
            }
        }
    }
    override fun startSession(goal: String, durationMinutes: Int, mode: FocusMode): FocusSession {
        check(ready) { "Session storage is loading" }
        return super.startSession(goal, durationMinutes, mode)
    }
    override fun onStateChanged() {
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }
    override fun onSessionBoundary(elapsed: Long, sessionId: String?) {
        ShortVideoRepository.sessionChanged(elapsed, sessionId)
    }
    private fun publish() {
        advanceIntervention()
        val day = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val from = day.timeInMillis
        day.add(Calendar.DAY_OF_MONTH, 1)
        val session = currentSession()
        val intervention = interventionState()
        mutableState.value = FocusUiState(session, remainingMillis(), dailyStatistics(from, day.timeInMillis),
            storedEvents().filter { it.timestamp >= from && it.timestamp < day.timeInMillis }.takeLast(4).asReversed(),
            loading = false, storageError = storageError, intervention = intervention,
            countdownMillis = countdownRemainingMillis(), days = weeklyStatistics(System.currentTimeMillis(), java.util.TimeZone.getDefault()),
            presets = store.presets(), schedules = store.schedules(), scheduleRuns = store.runs(),
            mindfulPromptVisible = intervention.hasOverlay && currentInterventionEvent()?.kind == EventKind.DM_CHECKIN)
    }
    fun whenReady(block: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { whenReady(block) }; return }
        if (ready) block() else readyCallbacks += block
    }
    fun presets() = store.presets()
    fun schedules() = store.schedules()
    fun savePreset(preset: FocusPreset) { check(ready); store.save(preset); onStateChanged(); FocusScheduler.reschedule(appContext) }
    fun deletePreset(id: String) {
        store.schedules().filter { it.presetId == id }.forEach { FocusScheduler.cancel(appContext, it.id) }
        store.deletePreset(id); onStateChanged()
    }
    fun saveSchedule(schedule: FocusSchedule) {
        check(ready); require(store.presets().any { it.id == schedule.presetId })
        FocusScheduler.cancel(appContext, schedule.id)
        store.save(schedule.copy(nextFireAt = null)); onStateChanged(); FocusScheduler.reschedule(appContext)
    }
    fun updateScheduleTime(schedule: FocusSchedule) { store.save(schedule); onStateChanged() }
    fun deleteSchedule(id: String) { FocusScheduler.cancel(appContext, id); store.deleteSchedule(id); onStateChanged() }
    fun recordRun(run: ScheduleRun) { store.save(run); FocusLog.write("SCHEDULE", run.result); onStateChanged() }
    /** Finish a receiver only after all queued durable writes. No database IO on the main thread. */
    fun flush(completed: () -> Unit) { worker.execute { handler.post { completed() } } }
    fun refreshState() { onStateChanged() }
}
