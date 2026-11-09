package com.example.waitasecond.ui.statistics

import android.app.Activity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.example.waitasecond.R
import com.example.waitasecond.core.monitoring.ShortVideoMetrics
import com.example.waitasecond.core.monitoring.ShortVideoPlatform
import com.example.waitasecond.core.session.FocusMode
import com.example.waitasecond.data.repository.FocusUiState
import com.example.waitasecond.data.repository.StatisticsRepository
import com.example.waitasecond.data.repository.ShortVideoRepository
import com.example.waitasecond.data.repository.ShortVideoUiState
import com.example.waitasecond.ui.components.AppTimeChartView
import com.example.waitasecond.ui.components.FocusFormat
import com.example.waitasecond.ui.components.TrackedAppView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Presentation only. Calendar boundaries, mode counts and successful outcomes come from the repository. */
class StatisticsPanel(private val activity: Activity, initialPeriod: Int) {
    var selectedPeriod = initialPeriod.coerceIn(0, 8)
        private set
    private var state = FocusUiState()
    private var labels = emptyList<String>()
    private val selector = activity.findViewById<Spinner>(R.id.statisticsPeriod)
    private val shortVideoOptIn = activity.findViewById<SwitchCompat>(R.id.shortVideoOptIn)
    private val badgeOptIn = activity.findViewById<SwitchCompat>(R.id.shortVideoBadgeOptIn)
    init {
        selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedPeriod = position
                render()
            }
        }
        shortVideoOptIn.setOnCheckedChangeListener { _, enabled -> ShortVideoRepository.setEnabled(enabled) }
        badgeOptIn.setOnCheckedChangeListener { _, enabled -> ShortVideoRepository.setBadgeEnabled(enabled) }
    }
    fun bindShortVideo(state: ShortVideoUiState, monitoringEnabled: Boolean) {
        shortVideoOptIn.isChecked = state.enabled
        shortVideoOptIn.isEnabled = state.ready
        badgeOptIn.visibility = if (state.enabled) View.VISIBLE else View.GONE
        badgeOptIn.isChecked = state.badgeEnabled
        badgeOptIn.isEnabled = state.ready
        text(R.id.shortVideoStatus, activity.getString(when {
            !state.ready -> R.string.loading_session
            !state.enabled -> R.string.short_video_disabled
            !monitoringEnabled -> R.string.short_video_service_needed
            else -> R.string.short_video_enabled
        }))
        val reels = state.metrics.today[ShortVideoPlatform.REELS] ?: ShortVideoMetrics()
        val shorts = state.metrics.today[ShortVideoPlatform.SHORTS] ?: ShortVideoMetrics()
        fun duration(millis: Long) = FocusFormat.timer(millis / 1_000 * 1_000)
        fun transitions(count: Int) = activity.resources.getQuantityString(R.plurals.short_video_transition_count, count, count)
        val episodes = reels.episodes + shorts.episodes
        text(R.id.shortVideoToday, activity.getString(R.string.short_video_today,
            duration(reels.viewerMillis), transitions(reels.transitions), duration(shorts.viewerMillis), transitions(shorts.transitions),
            duration(reels.viewerMillis + shorts.viewerMillis), activity.resources.getQuantityString(R.plurals.short_video_episode_count, episodes, episodes),
            duration(state.metrics.outsideFocusTodayMillis)))
        val session = activity.findViewById<TextView>(R.id.shortVideoSession)
        session.visibility = if (state.metrics.sessionId != null) View.VISIBLE else View.GONE
        session.text = activity.getString(R.string.short_video_session,
            duration(state.metrics.session.viewerMillis), transitions(state.metrics.session.transitions))
    }
    fun bind(value: FocusUiState) {
        state = value
        if (value.loading) return
        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        val nextLabels = listOf(activity.getString(R.string.last_seven_days)) + state.days.asReversed().mapIndexed { i, day ->
            when (i) { 0 -> activity.getString(R.string.today); 1 -> activity.getString(R.string.yesterday); else -> dateFormat.format(Date(day.start)) }
        }
        if (nextLabels != labels) {
            labels = nextLabels
            selector.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
            selector.setSelection(selectedPeriod.coerceAtMost(labels.lastIndex))
        }
        render()
    }
    private fun text(id: Int, value: CharSequence) { activity.findViewById<TextView>(id).text = value }
    private fun render() {
        val selected = if (selectedPeriod == 0) state.days.takeLast(7)
            else listOfNotNull(state.days.asReversed().getOrNull(selectedPeriod - 1))
        val stats = StatisticsRepository.combine(selected)
        text(R.id.statisticsScope, labels.getOrNull(selectedPeriod).orEmpty())
        text(R.id.weeklyFocusTotal, FocusFormat.duration(stats.studyMillis))
        text(R.id.modeUsage, activity.getString(R.string.mode_usage,
            selected.sumOf { it.sessionsByMode[FocusMode.LIGHT] ?: 0 }, selected.sumOf { it.sessionsByMode[FocusMode.STRICT] ?: 0 }))
        activity.findViewById<WeeklyFocusChartView>(R.id.weeklyFocusChart).bind(state.days.takeLast(7))
        text(R.id.screenTimeTotal, FocusFormat.duration(stats.screenMillis))
        activity.findViewById<AppTimeChartView>(R.id.screenTimeChart).bind(stats.monitoredAppMillis["YouTube"] ?: 0, stats.monitoredAppMillis["Instagram"] ?: 0)
        text(R.id.statsAttemptValue, stats.attempts.toString())
        text(R.id.attemptSummary, activity.getString(R.string.period_attempts, stats.attempts))
        text(R.id.dmCheckinCounts, activity.getString(R.string.dm_stats,stats.dmCheckins,stats.dmWorkConfirmed,stats.dmReturned))
        text(R.id.redirectSummary, activity.getString(R.string.redirect_summary, stats.redirects))
        listOf("YouTube" to R.id.statsYoutube, "Instagram" to R.id.statsInstagram).forEach { (app, viewId) ->
            val count = stats.attemptsByApp[app] ?: 0
            activity.findViewById<TrackedAppView>(viewId).bind(app,
                activity.resources.getQuantityString(R.plurals.app_attempts, count, count),
                activity.getString(R.string.percent_format, stats.percentage(app)))
        }
        text(R.id.sourceInsight, activity.getString(when {
            stats.attempts == 0 -> R.string.insight_empty
            (stats.attemptsByApp["YouTube"] ?: 0) > (stats.attemptsByApp["Instagram"] ?: 0) -> R.string.insight_youtube
            (stats.attemptsByApp["Instagram"] ?: 0) > (stats.attemptsByApp["YouTube"] ?: 0) -> R.string.insight_instagram
            else -> R.string.insight_equal
        }))
        // Recent events remain today-scoped and explicitly labeled; historical counts use the selected period.
        text(R.id.recentActivity, state.events.joinToString("\n") {
            "${it.app.displayName} · ${it.mode.name.lowercase()} · ${it.section.name.replace('_', ' ')}\n${it.outcome.name.replace('_', ' ')}"
        }.ifEmpty { activity.getString(R.string.no_recent_activity) })
    }
}
