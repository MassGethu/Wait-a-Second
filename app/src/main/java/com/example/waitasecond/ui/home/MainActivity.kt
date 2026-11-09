package com.example.waitasecond.ui.home

import com.example.waitasecond.R
import com.example.waitasecond.core.session.SessionStatus
import com.example.waitasecond.core.session.FocusMode
import com.example.waitasecond.core.distraction.InterventionPhase
import com.example.waitasecond.data.model.EventOutcome
import com.example.waitasecond.ui.statistics.StatisticsPanel
import com.example.waitasecond.ui.settings.FocusOptionsActivity
import android.widget.RadioGroup
import com.example.waitasecond.core.session.SessionTracker
import com.example.waitasecond.data.repository.FocusUiState
import com.example.waitasecond.data.repository.SessionManager
import com.example.waitasecond.data.repository.ShortVideoRepository
import com.example.waitasecond.service.accessibility.WaitaSecondAccessibilityService
import com.example.waitasecond.ui.components.TrackedAppView
import com.example.waitasecond.ui.components.FocusFormat
import com.example.waitasecond.ui.components.FocusTimerView
import com.example.waitasecond.util.FocusWindow

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var selectedMode: FocusMode? = null
    private lateinit var statisticsPanel: StatisticsPanel
    private var resumed = false
    private var selectedTopic = "DBMS"
    private var durationMinutes = 25
    private var showingStatistics = false
    private var monitoringEnabled = false
    private lateinit var viewModel: FocusViewModel
    private var uiState = FocusUiState()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = androidx.lifecycle.ViewModelProvider(this)[FocusViewModel::class.java]
        setContentView(R.layout.activity_main)
        FocusWindow.apply(this, findViewById(R.id.screenRoot))
        selectedTopic = savedInstanceState?.getString("topic") ?: "DBMS"
        durationMinutes = savedInstanceState?.getInt("duration", 25) ?: 25
        showingStatistics = savedInstanceState?.getBoolean("statistics") ?: false
        selectedMode = savedInstanceState?.getString("mode")?.let(FocusMode::valueOf)
        statisticsPanel = StatisticsPanel(this, savedInstanceState?.getInt("statisticsPeriod", 0) ?: 0)
        ShortVideoRepository.state.observe(this) { statisticsPanel.bindShortVideo(it, monitoringEnabled) }
        findViewById<RadioGroup>(R.id.focusModeGroup).setOnCheckedChangeListener { _, id ->
            selectedMode = when (id) { R.id.modeLight -> FocusMode.LIGHT; R.id.modeStrict -> FocusMode.STRICT; else -> null }
            findViewById<View>(R.id.planningButton)?.isEnabled = !uiState.loading
        findViewById<View>(R.id.startSessionButton).isEnabled = !uiState.loading && selectedMode != null
        }
        selectedMode?.let { findViewById<RadioGroup>(R.id.focusModeGroup).check(if (it == FocusMode.LIGHT) R.id.modeLight else R.id.modeStrict) }
        findViewById<Button>(R.id.resetEndSession).setOnClickListener { viewModel.endSession() }
        findViewById<Button>(R.id.navHome).setOnClickListener { selectPage(false) }
        findViewById<Button>(R.id.navStatistics).setOnClickListener { selectPage(true) }
        findViewById<Button>(R.id.editTopicButton).setOnClickListener { editTopic() }
        findViewById<Button>(R.id.durationButton).setOnClickListener { editDuration() }
        findViewById<Button>(R.id.startSessionButton).setOnClickListener {
            if (!SessionManager.isSessionActive()) {
                viewModel.startSession("Study $selectedTopic", durationMinutes, selectedMode ?: return@setOnClickListener)
                render()
            }
        }
        viewModel.state.observe(this) { state ->
            uiState = state
            val countdown = state.intervention.countdown
            if (resumed && state.intervention.phase == InterventionPhase.STRICT_FOCUS_RESET && countdown?.resumedAt == null) {
                state.intervention.token?.let { SessionManager.resetVisibility(it, true) }
            }
            render()
        }
        findViewById<Button>(R.id.endSessionButton).setOnClickListener {
            viewModel.endSession()
            render()
        }
        findViewById<Button>(R.id.monitoringButton).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.enable_monitoring)
                .setMessage(R.string.monitoring_disclosure)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    catch (_: android.content.ActivityNotFoundException) {
                        Toast.makeText(this, R.string.permission_settings_missing, Toast.LENGTH_LONG).show()
                    }
                }.setNegativeButton(R.string.cancel, null).show()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (uiState.intervention.phase == InterventionPhase.STRICT_FOCUS_RESET) return
                if (showingStatistics) selectPage(false) else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        findViewById<Button>(R.id.planningButton).setOnClickListener {
            startActivity(Intent(this,com.example.waitasecond.ui.planning.PlanningActivity::class.java))
        }
        findViewById<Button>(R.id.focusOptionsButton).setOnClickListener {
            startActivity(Intent(this, FocusOptionsActivity::class.java))
        }
        selectPage(showingStatistics)
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        SessionManager.foregroundChanged(SessionTracker.OWN_PACKAGE)
        val service = ComponentName(this, WaitaSecondAccessibilityService::class.java)
        monitoringEnabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == service } == true
        statisticsPanel.bindShortVideo(ShortVideoRepository.snapshot(), monitoringEnabled)
        SessionManager.confirmReturnToFocus()
        SessionManager.interventionState().takeIf { it.phase == InterventionPhase.STRICT_FOCUS_RESET }?.token
            ?.let { SessionManager.resetVisibility(it, true) }
        SessionManager.refreshState()
        render()
    }
    override fun onPause() {
        resumed = false
        SessionManager.interventionState().takeIf { it.phase == InterventionPhase.STRICT_FOCUS_RESET }?.token
            ?.let { SessionManager.resetVisibility(it, false) }
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("topic", selectedTopic); outState.putInt("duration", durationMinutes)
        outState.putBoolean("statistics", showingStatistics)
        outState.putString("mode", selectedMode?.name)
        outState.putInt("statisticsPeriod", statisticsPanel.selectedPeriod)
        super.onSaveInstanceState(outState)
    }
    private fun selectPage(statistics: Boolean) {
        showingStatistics = statistics
        findViewById<View>(R.id.homePage).visibility = if (statistics) View.GONE else View.VISIBLE
        findViewById<View>(R.id.statisticsPage).visibility = if (statistics) View.VISIBLE else View.GONE
        findViewById<View>(R.id.navHome).isSelected = !statistics
        findViewById<View>(R.id.navStatistics).isSelected = statistics
        if (statistics) statisticsPanel.bindShortVideo(ShortVideoRepository.snapshot(), monitoringEnabled)
        render()
    }
    private fun text(id: Int, value: CharSequence) { findViewById<TextView>(id).text = value }
    private fun render() {
        val reset = uiState.intervention.phase == InterventionPhase.STRICT_FOCUS_RESET
        findViewById<View>(R.id.focusResetPage).visibility = if (reset) View.VISIBLE else View.GONE
        findViewById<View>(R.id.mainNavigation).visibility = if (reset) View.GONE else View.VISIBLE
        findViewById<View>(R.id.homePage).visibility = if (!reset && !showingStatistics) View.VISIBLE else View.GONE
        findViewById<View>(R.id.statisticsPage).visibility = if (!reset && showingStatistics) View.VISIBLE else View.GONE
        if (reset) {
            showingStatistics = false
            text(R.id.resetTopic, FocusFormat.topic(uiState.session?.goal.orEmpty()))
            text(R.id.resetCountdown, ((uiState.countdownMillis + 999) / 1_000).toString())
        }
        val session = uiState.session?.takeIf { it.isOpen }
        val stats = uiState.statistics
        if (session != null) {
            selectedTopic = FocusFormat.topic(session.goal); durationMinutes = session.durationMinutes
            selectedMode = session.mode
            findViewById<RadioGroup>(R.id.focusModeGroup).check(if (session.mode == FocusMode.LIGHT) R.id.modeLight else R.id.modeStrict)
        }
        findViewById<View>(R.id.modeLight).isEnabled = session == null
        findViewById<View>(R.id.modeStrict).isEnabled = session == null
        findViewById<View>(R.id.planningButton)?.isEnabled = !uiState.loading
        findViewById<View>(R.id.startSessionButton).isEnabled = !uiState.loading && selectedMode != null
        text(R.id.topicValue, selectedTopic)
        findViewById<FocusTimerView>(R.id.focusTimer).bind(
            if (session != null) uiState.remainingMillis else durationMinutes * 60_000L,
            durationMinutes * 60_000L, session?.status ?: SessionStatus.IDLE)
        text(R.id.durationButton, getString(if (session != null) R.string.duration_active else R.string.duration_choice, durationMinutes))
        findViewById<View>(R.id.durationButton).isEnabled = session == null
        findViewById<View>(R.id.startSessionButton).visibility = if (session == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.endSessionButton).visibility = if (session != null) View.VISIBLE else View.GONE
        text(R.id.sessionHint, getString(when {
            uiState.loading -> R.string.loading_session
            uiState.storageError -> R.string.storage_error
            session != null && !monitoringEnabled -> R.string.monitoring_needed
            session != null -> R.string.active_hint
            uiState.session?.status == SessionStatus.CANCELLED -> R.string.cancelled_hint
            stats.hasSession -> R.string.finished_hint
            else -> R.string.ready_hint
        }))
        text(R.id.studyTimeValue, FocusFormat.duration(stats.studyMillis))
        text(R.id.attemptValue, stats.attempts.toString())
        text(R.id.redirectValue, stats.redirects.toString())
        findViewById<TrackedAppView>(R.id.trackedYoutube).bind("YouTube", getString(R.string.youtube_rule), getString(R.string.tracked_caption))
        findViewById<TrackedAppView>(R.id.trackedInstagram).bind("Instagram", getString(R.string.instagram_rule), getString(R.string.tracked_caption))
        findViewById<View>(R.id.monitoringButton).visibility = if (monitoringEnabled) View.GONE else View.VISIBLE
        if (session?.isActive == true && uiState.events.firstOrNull()?.let {
            it.sessionId == session.id && it.outcome == EventOutcome.RESET_COMPLETED
        } == true) text(R.id.sessionHint, getString(R.string.back_to_topic, FocusFormat.topic(session.goal)))
        statisticsPanel.bind(uiState)
    }
    private fun editTopic() {
        if (SessionManager.openSession() != null) {
            Toast.makeText(this, R.string.topic_locked, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setText(selectedTopic); hint = getString(R.string.topic_dialog_hint)
            filters = arrayOf(InputFilter.LengthFilter(80)); isSingleLine = true
            setSelectAllOnFocus(true)
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.topic_dialog_title).setView(input)
            .setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val candidate = input.text.toString().trim()
                if (!SessionTracker.isSupportedTopic(candidate)) input.error = getString(R.string.topic_error)
                else { selectedTopic = candidate; dialog.dismiss(); render() }
            }
        }
        dialog.show()
    }
    private fun editDuration() {
        if (SessionManager.openSession() != null) return
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(durationMinutes.toString()); isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(3))
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.duration_title).setView(input)
            .setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = input.text.toString().toIntOrNull()
                if (minutes == null || minutes !in 1..999) input.error = getString(R.string.duration_required_error)
                else { durationMinutes = minutes; dialog.dismiss(); render() }
            }
        }
        dialog.show()
    }
}
