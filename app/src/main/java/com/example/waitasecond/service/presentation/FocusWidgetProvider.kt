package com.example.waitasecond.service.presentation

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.example.waitasecond.R
import com.example.waitasecond.core.session.FocusSession
import com.example.waitasecond.data.repository.SessionManager

class FocusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) = refresh()
    override fun onDisabled(context: Context) = refresh()
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, options: Bundle) =
        refresh()

    private fun refresh() {
        val pending = goAsync()
        SessionManager.whenReady {
            try {
                SessionManager.refreshFocusSurfaces()
            } finally {
                pending?.finish()
            }
        }
    }

    internal companion object {
        fun publish(context: Context, session: FocusSession?): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            val ids = manager.getAppWidgetIds(ComponentName(context, FocusWidgetProvider::class.java))
            if (ids.isEmpty()) return false
            manager.updateAppWidget(ids, RemoteViews(context.packageName, R.layout.focus_widget).apply {
                setOnClickPendingIntent(android.R.id.background, FocusSessionSurfaces.openFocusIntent(context))
                setViewVisibility(R.id.focusWidgetStart, if (session == null) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.focusWidgetActive, if (session == null) View.GONE else View.VISIBLE)
                setTextViewText(R.id.focusWidgetGoal, session?.goal.orEmpty())
                setChronometer(R.id.focusWidgetTimer, session?.elapsedBaseMillis ?: 0L, null, session != null)
            })
            return true
        }
    }
}
