package com.example.waitasecond

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

class WaitaSecondAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false
    private val lastTreeLog = mutableMapOf<String, Long>()
    private val scan = Runnable { scanPending = false; collectCurrentState() }
    // Also checks expiry/screen-off when no third-party events arrive. No Activity dependency.
    private val heartbeat = object : Runnable {
        override fun run() { collectCurrentState(); handler.postDelayed(this, 1_000) }
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
        Log.i("WaitaSecondAccessibility", "Accessibility service connected")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Events are wake-up hints only, never activity records or foreground authority.
        if (event.eventType !in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_SELECTED)) return
        if (!scanPending && SessionManager.isSessionActive()) {
            scanPending = true
            handler.postDelayed(scan, 250)
        }
    }
    private fun collectCurrentState() {
        if (!SessionManager.isSessionActive()) return
        if (!getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            SessionManager.foregroundChanged("screen.off")
            return
        }
        try {
            val availableWindows = windows
            val activeRoot = rootInActiveWindow
            val activeWindow = availableWindows.firstOrNull { it.id == activeRoot?.windowId }
            // OEM window inventories can lag the active root. Absence is not evidence
            // that the root is invalid. For an IME/system root, use the focused app window.
            val root = if (activeRoot != null && (activeWindow == null ||
                    activeWindow.type == AccessibilityWindowInfo.TYPE_APPLICATION)) activeRoot
                else availableWindows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (it.isFocused || it.isActive) }?.root ?: return
            val pkg = root.packageName?.toString() ?: return
            // Our Activities explicitly identify normal vs intervention windows in onResume.
            if (pkg == SessionTracker.OWN_PACKAGE) return
            if (pkg == "com.android.systemui" || pkg == currentInputMethodPackage()) return
            SessionManager.foregroundChanged(pkg)
            if (SessionManager.isInterventionVisible()) return
            if (pkg != SessionTracker.YOUTUBE_PACKAGE && pkg != SessionTracker.INSTAGRAM_PACKAGE) return
            val nodes = AccessibilityNodeInspector.inspect(root)
            logTree(pkg, nodes)
            when (pkg) {
                SessionTracker.YOUTUBE_PACKAGE -> PrototypePipeline.processYouTube(this, nodes)
                SessionTracker.INSTAGRAM_PACKAGE -> PrototypePipeline.processInstagram(this, nodes)
            }
        } catch (error: IllegalStateException) {
            Log.d("WaitaSecondAccessibility", "Stale accessibility window; waiting for next scan", error)
        }
    }
    private fun currentInputMethodPackage(): String? = android.provider.Settings.Secure.getString(
        contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')

    private fun logTree(pkg: String, nodes: List<AccessibilityNodeSnapshot>) {
        val now = SystemClock.elapsedRealtime()
        if (now - (lastTreeLog[pkg] ?: -10_000L) < 10_000) return
        lastTreeLog[pkg] = now
        // IDs/hierarchy support rule tuning without dumping private DM text or message contents.
        nodes.take(60).chunked(6).forEach { chunk ->
            Log.d("AccessibilityTree", "$pkg\n" + chunk.joinToString("\n") {
                "id=${it.viewId} ancestors=${it.ancestorIds.takeLast(3)} class=${it.className} depth=${it.depth} selected=${it.isSelected} focused=${it.isFocused} editable=${it.isEditable}"
            })
        }
    }
    override fun onInterrupt() { Log.i("WaitaSecondAccessibility", "Service interrupted") }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        SessionManager.foregroundChanged("monitor.unavailable")
        super.onDestroy()
    }
}
