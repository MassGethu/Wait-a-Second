package com.example.waitasecond

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var selectedTopic = "DBMS"
    private var durationMinutes = 25
    private var showingStatistics = false
    private var monitoringEnabled = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1_000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        FocusWindow.apply(this, findViewById(R.id.screenRoot))
        selectedTopic = savedInstanceState?.getString("topic") ?: "DBMS"
        durationMinutes = savedInstanceState?.getInt("duration", 25) ?: 25
        showingStatistics = savedInstanceState?.getBoolean("statistics") ?: false
        findViewById<Button>(R.id.navHome).setOnClickListener { selectPage(false) }
        findViewById<Button>(R.id.navStatistics).setOnClickListener { selectPage(true) }
        findViewById<Button>(R.id.editTopicButton).setOnClickListener { editTopic() }
        findViewById<Button>(R.id.durationButton).setOnClickListener { editDuration() }
        findViewById<Button>(R.id.startSessionButton).setOnClickListener {
            if (!SessionManager.isSessionActive()) {
                SessionManager.startSession("Study $selectedTopic", durationMinutes)
                render()
            }
        }
        findViewById<Button>(R.id.endSessionButton).setOnClickListener {
            SessionManager.endSession()
            render()
        }
        findViewById<Button>(R.id.monitoringButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingStatistics) selectPage(false) else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        selectPage(showingStatistics)
    }
    override fun onResume() {
        super.onResume()
        SessionManager.foregroundChanged(SessionTracker.OWN_PACKAGE)
        val service = ComponentName(this, WaitaSecondAccessibilityService::class.java)
        monitoringEnabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == service } == true
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("topic", selectedTopic); outState.putInt("duration", durationMinutes)
        outState.putBoolean("statistics", showingStatistics)
        super.onSaveInstanceState(outState)
    }
    private fun selectPage(statistics: Boolean) {
        showingStatistics = statistics
        findViewById<View>(R.id.homePage).visibility = if (statistics) View.GONE else View.VISIBLE
        findViewById<View>(R.id.statisticsPage).visibility = if (statistics) View.VISIBLE else View.GONE
        findViewById<View>(R.id.navHome).isSelected = !statistics
        findViewById<View>(R.id.navStatistics).isSelected = statistics
        render()
    }
    private fun text(id: Int, value: CharSequence) { findViewById<TextView>(id).text = value }
    private fun render() {
        val session = SessionManager.activeSession()
        val stats = SessionManager.statistics()
        if (session != null) { selectedTopic = FocusFormat.topic(session.goal); durationMinutes = session.durationMinutes }
        text(R.id.topicValue, selectedTopic)
        findViewById<FocusTimerView>(R.id.focusTimer).bind(
            if (session != null) SessionManager.remainingMillis() else durationMinutes * 60_000L,
            durationMinutes * 60_000L, session != null)
        text(R.id.durationButton, getString(if (session != null) R.string.duration_active else R.string.duration_choice, durationMinutes))
        findViewById<View>(R.id.durationButton).isEnabled = session == null
        findViewById<View>(R.id.startSessionButton).visibility = if (session == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.endSessionButton).visibility = if (session != null) View.VISIBLE else View.GONE
        text(R.id.sessionHint, getString(when {
            session != null && !monitoringEnabled -> R.string.monitoring_needed
            session != null -> R.string.active_hint
            stats.hasSession -> R.string.finished_hint
            else -> R.string.ready_hint
        }))
        text(R.id.studyTimeValue, FocusFormat.duration(stats.studyMillis))
        text(R.id.attemptValue, stats.attempts.toString())
        text(R.id.redirectValue, stats.redirects.toString())
        findViewById<TrackedAppView>(R.id.trackedYoutube).bind("YouTube", getString(R.string.youtube_rule), getString(R.string.tracked_caption))
        findViewById<TrackedAppView>(R.id.trackedInstagram).bind("Instagram", getString(R.string.instagram_rule), getString(R.string.tracked_caption))
        findViewById<View>(R.id.monitoringButton).visibility = if (monitoringEnabled) View.GONE else View.VISIBLE
        text(R.id.statisticsScope, getString(if (session != null) R.string.this_session else if (stats.hasSession) R.string.last_session else R.string.no_session))
        text(R.id.screenTimeTotal, FocusFormat.duration(stats.screenMillis))
        findViewById<AppTimeChartView>(R.id.screenTimeChart).bind(stats.monitoredAppMillis["YouTube"] ?: 0, stats.monitoredAppMillis["Instagram"] ?: 0)
        text(R.id.statsAttemptValue, stats.attempts.toString())
        text(R.id.attemptSummary, if (stats.attempts == 0) getString(R.string.no_distractions) else
            resources.getQuantityString(R.plurals.attempt_summary, stats.attempts, stats.attempts))
        text(R.id.redirectSummary, getString(R.string.redirect_summary, stats.redirects))
        listOf("YouTube" to R.id.statsYoutube, "Instagram" to R.id.statsInstagram).forEach { (app, viewId) ->
            val count = stats.attemptsByApp[app] ?: 0
            findViewById<TrackedAppView>(viewId).bind(app, resources.getQuantityString(R.plurals.app_attempts, count, count),
                getString(R.string.percent_format, stats.percentage(app)))
        }
        val youtube = stats.attemptsByApp["YouTube"] ?: 0
        val instagram = stats.attemptsByApp["Instagram"] ?: 0
        text(R.id.sourceInsight, getString(when {
            stats.attempts == 0 -> R.string.insight_empty
            youtube > instagram -> R.string.insight_youtube
            instagram > youtube -> R.string.insight_instagram
            else -> R.string.insight_equal
        }))
        text(R.id.recentActivity, SessionManager.eventsSnapshot().filter { it.sourceApp == "YouTube" || it.sourceApp == "Instagram" }
            .takeLast(4).asReversed().joinToString("\n") {
                val outcome = if (it.blocked) "Intervened" else if (it.sourceApp == "Instagram") "Allowed" else
                    it.status.name.lowercase().replaceFirstChar(Char::uppercase)
                "${it.sourceApp} · ${it.content.replace('_', ' ')}\n$outcome"
            }.ifEmpty { getString(R.string.no_recent_activity) })
    }
    private fun editTopic() {
        if (SessionManager.isSessionActive()) {
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
        if (SessionManager.isSessionActive()) return
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
