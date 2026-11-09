package com.example.waitasecond.service.overlay

import com.example.waitasecond.R
import com.example.waitasecond.core.monitoring.AppPackages
import com.example.waitasecond.core.monitoring.OverlayController
import com.example.waitasecond.core.monitoring.OverlayData
import com.example.waitasecond.data.model.UserChoice
import com.example.waitasecond.data.repository.FocusLog
import com.example.waitasecond.data.repository.FocusUiState
import com.example.waitasecond.data.repository.SessionManager
import com.example.waitasecond.ui.components.FocusFormat
import com.example.waitasecond.core.session.FocusMode
import com.example.waitasecond.core.distraction.DmDwellPolicy
import com.example.waitasecond.core.distraction.InterventionPhase

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.content.Intent
import com.example.waitasecond.ui.retype.RetypeActivity

/** Own app window, attached to the user-enabled service; no target-app task manipulation. */
class AccessibilityOverlayController(private val service: AccessibilityService,
    private val navigator: FocusNavigator = AndroidFocusNavigator(service)) : OverlayController {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var data: OverlayData? = null
    private var removeBackCallback: (() -> Unit)? = null
    private val dismissDm = Runnable { if (isDmCheckinShowing()) dismissOverlay() }
    override fun showOverlay(data: OverlayData) {
        if (data.isDmCheckin) attachDmCheckin(data) else attachOverlay(data, false)
    }
    fun remindReset(data: OverlayData) = attachOverlay(data, true)
    fun restore(data: OverlayData) = attachOverlay(data, false, true)
    private fun attachOverlay(data: OverlayData, reminder: Boolean, restoring: Boolean = false) {
        if (view != null) return
        if (reminder) {
            if (!SessionManager.remindStrictReset(data.token)) return
        } else if (!restoring && !SessionManager.interventionRequested(data.token)) return
        try {
            val themedContext = ContextThemeWrapper(service, R.style.Theme_WaitASecond)
            val root = LayoutInflater.from(themedContext)
                .inflate(R.layout.overlay_intervention, android.widget.FrameLayout(themedContext), false)
            fun text(id: Int, value: String) { root.findViewById<TextView>(id).text = value }
            val topic = FocusFormat.topic(data.topic)
            text(R.id.interventionGoal, topic)
            text(R.id.interventionMessage, service.getString(R.string.overlay_message, topic))
            text(R.id.interventionSource, data.context.app.displayName)
            text(R.id.interventionContent, data.context.section.name.replace('_', ' '))
            text(R.id.interventionRemaining, FocusFormat.timer(data.remainingMillis))
            root.findViewById<Button>(R.id.continueButton).setOnClickListener {
                beginRetype(data)
            }
            root.findViewById<Button>(R.id.goBackButton).setOnClickListener {
                if (!SessionManager.chooseIntervention(data.token, UserChoice.GO_BACK)) return@setOnClickListener
                if (navigator.openSession()) detachWindow()
                else {
                    SessionManager.interventionNavigationFailed(data.token)
                    text(R.id.interventionContent, service.getString(R.string.return_failed))
                }
            }
            fun handleBack() {
                if (SessionManager.interventionState().canContinue) beginRetype(data)
            }
            root.isFocusableInTouchMode = true
            root.setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_BACK) {
                    // Back must not be a hidden bypass. Light uses the same friction; Strict consumes it.
                    if (event.action == KeyEvent.ACTION_UP) handleBack()
                    true
                } else false
            }
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT).apply { dimAmount = 0.65f }
            windows.addView(root, params)
            view = root
            this.data = data
            root.requestFocus()
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val dispatcher = root.findOnBackInvokedDispatcher()
                val callback = android.window.OnBackInvokedCallback { handleBack() }
                dispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
                removeBackCallback = { dispatcher?.unregisterOnBackInvokedCallback(callback) }
            }
            if (!reminder && !restoring && !SessionManager.interventionLaunched(data.token)) dismissOverlay()
            else {
                SessionManager.foregroundChanged(AppPackages.OWN, interventionWindow = true)
                renderIntervention()
                FocusLog.write("OVERLAY", "Accessibility window attached; no Activity/HOME/BACK navigation")
            }
        } catch (_: RuntimeException) {
            dismissOverlay()
            SessionManager.interventionLaunchFailed(data.token)
            FocusLog.write("OVERLAY", "Window could not be attached")
        }
    }
    private fun attachDmCheckin(data: OverlayData) {
        if (view != null || !SessionManager.interventionRequested(data.token)) return
        try {
            val themedContext = ContextThemeWrapper(service, R.style.Theme_WaitASecond)
            val root = LayoutInflater.from(themedContext)
                .inflate(R.layout.overlay_dm_checkin, android.widget.FrameLayout(themedContext), false)
            root.findViewById<TextView>(R.id.dmGoal).text = service.getString(R.string.dm_checkin_goal, data.topic)
            root.findViewById<Button>(R.id.dmRelevant).setOnClickListener {
                if (SessionManager.chooseIntervention(data.token, UserChoice.WORK_CONFIRMED)) detachWindow()
            }
            root.findViewById<Button>(R.id.dmPersonal).setOnClickListener {
                if (SessionManager.chooseIntervention(data.token, UserChoice.PERSONAL_CONFIRMED)) detachWindow()
            }
            root.findViewById<Button>(R.id.dmReturn).setOnClickListener {
                if (!SessionManager.chooseIntervention(data.token, UserChoice.GO_BACK)) return@setOnClickListener
                if (navigator.openSession()) detachWindow() else SessionManager.interventionNavigationFailed(data.token)
            }
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP
                y = service.resources.getDimensionPixelSize(R.dimen.dm_checkin_offset)
            }
            windows.addView(root, params)
            view = root
            this.data = data
            if (!SessionManager.interventionLaunched(data.token)) dismissOverlay()
            else root.postDelayed(dismissDm, DmDwellPolicy.PROMPT_VISIBLE_MS)
        } catch (_: RuntimeException) {
            dismissOverlay()
            SessionManager.interventionLaunchFailed(data.token)
            FocusLog.write("OVERLAY", "Messaging check-in could not be attached")
        }
    }
    fun refresh(state: FocusUiState) {
        if (state.session?.isActive != true || data?.token?.let(SessionManager::isCurrentIntervention) != true) {
            dismissOverlay()
        } else if (!isDmCheckinShowing()) {
            view?.findViewById<TextView>(R.id.interventionRemaining)?.text = FocusFormat.timer(state.remainingMillis)
            renderIntervention()
        }
    }
    override fun dismissOverlay() {
        data?.let {
            if (SessionManager.interventionState().hasOverlay) SessionManager.closeIntervention(it.token)
        }
        detachWindow()
    }
    private fun beginRetype(data: OverlayData) {
        if (!SessionManager.chooseIntervention(data.token, UserChoice.CONTINUE_ANYWAY)) return
        try {
            service.startActivity(Intent(service, RetypeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                putExtra(RetypeActivity.EXTRA_TOKEN, data.token)
            })
            detachWindow()
        } catch (_: RuntimeException) {
            SessionManager.cancelRetype(data.token)
            FocusLog.write("OVERLAY", "Typing screen unavailable; intervention retained")
        }
    }
    private fun renderIntervention() {
        val root = view ?: return
        val state = SessionManager.interventionState()
        root.findViewById<Button>(R.id.continueButton).apply {
            visibility = if (state.mode == FocusMode.LIGHT) View.VISIBLE else View.GONE
            isEnabled = state.canContinue
        }
        root.findViewById<TextView>(R.id.interventionMode).text = service.getString(
            if (state.mode == FocusMode.STRICT) R.string.strict_overlay_hint else R.string.light_overlay_hint)
    }
    private fun detachWindow() {
        removeBackCallback?.invoke(); removeBackCallback = null
        view?.let { root ->
            root.removeCallbacks(dismissDm)
            try { windows.removeViewImmediate(root) } catch (_: IllegalArgumentException) { /* Already detached by Android. */ }
        }
        if (view != null) FocusLog.write("OVERLAY", "dismissed")
        view = null; data = null
    }
    fun isShowing() = view != null
    fun isDmCheckinShowing() = data?.isDmCheckin == true
    fun isBlockingShowing() = isShowing() && !isDmCheckinShowing()
    fun targetApp() = data?.context?.app
}
