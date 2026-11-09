package com.example.waitasecond.service.accessibility

import com.example.waitasecond.core.distraction.InterventionPhase
import com.example.waitasecond.core.monitoring.OverlayData
import com.example.waitasecond.core.monitoring.AppContext
import com.example.waitasecond.core.monitoring.AppPackages
import com.example.waitasecond.core.monitoring.AppSection
import com.example.waitasecond.core.monitoring.MonitoringPipeline
import com.example.waitasecond.core.monitoring.MonitoringPolicy
import com.example.waitasecond.core.monitoring.SupportedApp
import com.example.waitasecond.core.monitoring.ShortVideoPage
import com.example.waitasecond.core.monitoring.ShortVideoPlatform
import com.example.waitasecond.data.repository.FocusLog
import com.example.waitasecond.data.repository.FocusUiState
import com.example.waitasecond.data.repository.SessionManager
import com.example.waitasecond.data.repository.ShortVideoRepository
import com.example.waitasecond.data.repository.ShortVideoUiState
import com.example.waitasecond.service.overlay.AccessibilityOverlayController
import com.example.waitasecond.service.overlay.ShortVideoBadge

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer

class WaitaSecondAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var overlay: AccessibilityOverlayController
    private lateinit var shortVideoBadge: ShortVideoBadge
    private lateinit var pipeline: MonitoringPipeline
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var connected = false
    private var stopAudioObservation: (() -> Unit)? = null
    private var scanPending = false
    private var focusActive = false
    private var trackingEnabled = false
    private var keyboardVisible = false
    private var dmInteractionActive = false
    private var lastContext: AppContext? = null
    private var pendingPage: ShortVideoPage? = null
    private fun monitoring() = focusActive || trackingEnabled
    private val scan = Runnable { scanPending = false; collectCurrentState() }
    private val sessionObserver = Observer<FocusUiState> { state ->
        overlay.refresh(state)
        val active = !state.loading && state.session?.isActive == true
        if (active && !overlay.isShowing()) {
            if (state.intervention.hasOverlay || (!dmInteractionActive && SessionManager.dmCheckinDue())) scheduleScan()
        }
        if (active != focusActive) {
            focusActive = active
            lastContext = null
            handler.removeCallbacks(scan); scanPending = false
            if (monitoring()) scheduleScan() else ShortVideoRepository.observe(null)
        }
    }
    private val shortVideoObserver = Observer<ShortVideoUiState> { state ->
        shortVideoBadge.render(state)
        val enabled = state.ready && state.enabled
        if (enabled != trackingEnabled) {
            trackingEnabled = enabled
            pendingPage = null
            if (monitoring()) scheduleScan()
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> leaveForeground("screen.off")
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> ShortVideoRepository.clockChanged()
                else -> if (monitoring()) scheduleScan()
            }
        }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        if (connected) return
        overlay = AccessibilityOverlayController(this)
        shortVideoBadge = ShortVideoBadge(this)
        pipeline = MonitoringPipeline(SessionManager, overlay, log = FocusLog::write)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        connected = true
        if (Build.VERSION.SDK_INT >= 31) {
            stopAudioObservation = try { observeCallAudio() } catch (_: SecurityException) { null }
        }
        SessionManager.state.observeForever(sessionObserver)
        ShortVideoRepository.state.observeForever(shortVideoObserver)
        FocusLog.write("MONITOR", "service connected")
    }
    @RequiresApi(31)
    private fun observeCallAudio(): () -> Unit {
        val listener = AudioManager.OnModeChangedListener { mode ->
            if (connected && monitoring()) {
                if (hasCallAudio(mode)) leaveForeground("call.audio") else scheduleScan()
            }
        }
        audio.addOnModeChangedListener(mainExecutor, listener)
        return { audio.removeOnModeChangedListener(listener) }
    }
    private fun hasCallAudio(mode: Int) = when (mode) {
        AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_CALL_SCREENING, AudioManager.MODE_CALL_REDIRECT, AudioManager.MODE_COMMUNICATION_REDIRECT -> true
        else -> false
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!monitoring()) return
        // Other apps provide window-transition hints only; never inspect their content trees.
        val transition = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        val supported = SupportedApp.fromPackage(event.packageName?.toString())
        if (trackingEnabled && supported != null && event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            event.fromIndex >= 0 && event.fromIndex == event.toIndex && !overlay.isBlockingShowing()) {
            readViewerPage(supported, event)?.let { pendingPage = it }
        }
        if (transition || (supported != null && when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_SELECTED -> true
                else -> false
            })) scheduleScan()
    }
    private fun readViewerPage(app: SupportedApp, event: AccessibilityEvent): ShortVideoPage? {
        val platform = if (app == SupportedApp.INSTAGRAM) ShortVideoPlatform.REELS else ShortVideoPlatform.SHORTS
        fun read(id: String?) = ShortVideoPage.from(platform, event.windowId, id, event.fromIndex, event.toIndex)
        var source: AccessibilityNodeInfo? = null
        var parent: AccessibilityNodeInfo? = null
        try {
            source = event.source ?: return null
            read(source.viewIdResourceName)?.let { return it }
            // The observed Reels pager owns an anonymous RecyclerView, which may send the scroll.
            if (source.className?.toString() != "androidx.recyclerview.widget.RecyclerView") return null
            parent = source.parent
            return read(parent?.viewIdResourceName)
        } catch (_: SecurityException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        } finally {
            recycle(parent)
            recycle(source)
        }
    }
    private fun scheduleScan() {
        if (!scanPending) {
            scanPending = true
            handler.postDelayed(scan, MonitoringPolicy.SCAN_DEBOUNCE_MS)
        }
    }
    private fun collectCurrentState() {
        val page = pendingPage.also { pendingPage = null }
        val inFocus = SessionManager.isSessionActive()
        if (!inFocus && !trackingEnabled) return
        if (!getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            leaveForeground("screen.off"); return
        }
        if (hasCallAudio(audio.mode)) {
            leaveForeground("call.audio"); return
        }
        var activeRoot: AccessibilityNodeInfo? = null
        var fallbackRoot: AccessibilityNodeInfo? = null
        var availableWindows: List<AccessibilityWindowInfo> = emptyList()
        try {
            availableWindows = windows
            activeRoot = rootInActiveWindow
            val activeWindow = availableWindows.firstOrNull { it.id == activeRoot?.windowId }
            keyboardVisible = availableWindows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val applicationWindows = availableWindows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val root = when {
                (activeWindow?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && overlay.isShowing() &&
                    activeRoot?.packageName?.toString() == AppPackages.OWN) ||
                    activeWindow?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ->
                    (applicationWindows.firstOrNull { it.isFocused || it.isActive } ?: applicationWindows.firstOrNull())
                        ?.root.also { fallbackRoot = it }
                activeWindow == null || activeWindow.type == AccessibilityWindowInfo.TYPE_APPLICATION -> activeRoot
                else -> null
            }
            if (root == null) {
                leaveForeground("monitor.unknown")
                return
            }
            val pkg = root.packageName?.toString() ?: run { leaveForeground("monitor.unknown"); return }
            val app = SupportedApp.fromPackage(pkg)
            if (pkg == "com.android.systemui") {
                leaveForeground("system.window")
                return
            }
            if (pkg == currentInputMethodPackage()) {
                dmInteractionActive = true
                if (overlay.isDmCheckinShowing()) overlay.dismissOverlay()
                ShortVideoRepository.observe(null)
                return
            }
            if (overlay.isShowing()) {
                if (app != overlay.targetApp() || (overlay.isDmCheckinShowing() && keyboardVisible)) {
                    overlay.dismissOverlay()
                } else if (overlay.isBlockingShowing()) { ShortVideoRepository.observe(null); return }
            }
            SessionManager.foregroundChanged(pkg)
            if (app == null) {
                lastContext = null
                dmInteractionActive = false
                ShortVideoRepository.observe(null)
                return
            }
            val intervention = SessionManager.interventionState()
            if (intervention.phase == InterventionPhase.STRICT_FOCUS_RESET) {
                ShortVideoRepository.observe(null)
                // Returning through Recents must not bypass an unfinished reset. Reuse its event,
                // token and remaining countdown; this is not a new classification or attempt.
                val session = SessionManager.activeSession() ?: return
                val event = SessionManager.storedEvents().lastOrNull { it.sessionId == session.id } ?: return
                overlay.remindReset(OverlayData(intervention.token ?: return, session.goal,
                    AppContext(app, if (app == event.app) event.section else AppSection.UNKNOWN),
                    SessionManager.remainingMillis()))
                return
            }
            if (intervention.hasOverlay && !overlay.isDmCheckinShowing()) {
                ShortVideoRepository.observe(null)
                val event = SessionManager.currentInterventionEvent() ?: return
                val session = SessionManager.activeSession() ?: return
                if (event.app == app) overlay.restore(OverlayData(intervention.token ?: return, session.goal,
                    AppContext(event.app, event.section), SessionManager.remainingMillis()))
                return
            }
            val nodes = AccessibilityNodeInspector.inspect(root, structuralOnly = !inFocus)
            val context = ContextExtractor.extract(app, nodes)
            dmInteractionActive = keyboardVisible || (context.isDirectConversation && nodes.any {
                (it.isEditable && it.isFocused) || it.viewId.orEmpty().substringAfterLast('/') in DM_COMPOSER_SURFACES
            })
            if (overlay.isDmCheckinShowing() && (!context.isDirectConversation || dmInteractionActive)) overlay.dismissOverlay()
            if (inFocus) {
                if (lastContext != context) {
                    FocusLog.write("MONITOR", "${context.app}/${context.section}")
                    lastContext = context
                }
                pipeline.process(context)
                if (context.isDirectConversation && !dmInteractionActive) {
                    SessionManager.pollDmCheckin()?.let(overlay::showOverlay)
                }
            }
            if (overlay.isBlockingShowing()) ShortVideoRepository.observe(null)
            else ShortVideoRepository.observe(context, root.windowId, page)
        } catch (_: IllegalStateException) {
            FocusLog.write("MONITOR", "Stale window; awaiting next event")
            leaveForeground("monitor.unknown")
        } catch (_: SecurityException) {
            leaveForeground("monitor.unavailable")
        } finally {
            recycle(activeRoot)
            if (fallbackRoot !== activeRoot) recycle(fallbackRoot)
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT < 33) availableWindows.forEach { it.recycle() }
        }
    }
    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo?) {
        if (android.os.Build.VERSION.SDK_INT < 33) node?.recycle()
    }
    private fun currentInputMethodPackage(): String? = android.provider.Settings.Secure.getString(
        contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
    private fun leaveForeground(reason: String) {
        pendingPage = null
        keyboardVisible = false
        dmInteractionActive = false
        lastContext = null
        overlay.dismissOverlay()
        ShortVideoRepository.observe(null)
        SessionManager.foregroundChanged(reason)
    }
    override fun onInterrupt() {
        if (connected) leaveForeground("monitor.unavailable")
    }
    private fun disconnect() {
        if (!connected) return
        connected = false
        stopAudioObservation?.invoke()
        stopAudioObservation = null
        handler.removeCallbacksAndMessages(null)
        scanPending = false
        focusActive = false
        trackingEnabled = false
        SessionManager.state.removeObserver(sessionObserver)
        ShortVideoRepository.state.removeObserver(shortVideoObserver)
        shortVideoBadge.clear()
        leaveForeground("monitor.unavailable")
        unregisterReceiver(screenReceiver)
    }
    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private companion object {
        val DM_COMPOSER_SURFACES = setOf("message_actions_fragment", "direct_media_picker", "voice_message_recording")
    }
}
