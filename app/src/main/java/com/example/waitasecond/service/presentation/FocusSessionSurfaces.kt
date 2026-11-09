package com.example.waitasecond.service.presentation

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.waitasecond.R
import com.example.waitasecond.core.session.FocusSession
import com.example.waitasecond.service.scheduling.FocusScheduler
import com.example.waitasecond.service.scheduling.ScheduleReceiver
import com.example.waitasecond.ui.home.MainActivity

/** Publishes session boundaries. Android hosts advance the chronometers independently. */
internal class FocusSessionSurfaces(private val context: Context) {
    private data class Expiry(val deadline: Long, val exact: Boolean)

    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private var published = false
    private var displayedSession: FocusSession? = null
    private var expiry: Expiry? = null

    fun publish(session: FocusSession?, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val active = session?.takeIf { it.isActive && it.deadlineElapsedMillis > now }
        if (!force && published && displayedSession == active) return
        published = true
        displayedSession = active

        val hasWidget = FocusWidgetProvider.publish(context, active)
        val hasNotification = active != null && isLockScreenEnabled(context) && postTimer(active, now)
        if (!hasNotification) notifications.cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
        scheduleExpiry(active?.deadlineElapsedMillis?.takeIf { hasWidget || hasNotification })
    }

    private fun postTimer(session: FocusSession, now: Long): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!canPostLockScreenTimer(context)) return false
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(
                CHANNEL, context.getString(R.string.focus_display_channel), NotificationManager.IMPORTANCE_LOW
            ))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_focus)
            .setContentTitle(session.goal)
            .setContentText(context.getString(R.string.focus_display_notification_text))
            .setContentIntent(openFocusIntent(context))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis() - session.elapsedMillis(now))
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setTimeoutAfter(session.remainingMillis(now))
            .build()
        return try {
            notifications.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun scheduleExpiry(deadline: Long?) {
        if (deadline == null) {
            expiryIntent(PendingIntent.FLAG_NO_CREATE)?.let(alarms::cancel)
            expiry = null
            return
        }
        val target = Expiry(deadline, FocusScheduler.exactAllowed(context))
        if (target == expiry) return
        val intent = expiryIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        if (target.exact) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, intent)
                expiry = target
                return
            } catch (_: SecurityException) {
                // Exact access may have changed after checking it.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, intent)
        expiry = target.copy(exact = false)
    }

    private fun expiryIntent(flags: Int) = PendingIntent.getBroadcast(
        context, 0, Intent(context, ScheduleReceiver::class.java).setAction(ACTION_EXPIRE),
        flags or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        const val ACTION_OPEN = "com.example.waitasecond.OPEN_FOCUS"
        const val ACTION_EXPIRE = "com.example.waitasecond.FOCUS_DISPLAY_EXPIRE"
        private const val CHANNEL = "focus_session_timer"
        private const val NOTIFICATION_TAG = "focus_session_timer"
        private const val NOTIFICATION_ID = 1
        private const val LOCK_SCREEN_OPT_IN = "lock_screen_focus_timer"

        private fun options(context: Context) = context.getSharedPreferences("focus_options", Context.MODE_PRIVATE)
        fun isLockScreenEnabled(context: Context) = options(context).getBoolean(LOCK_SCREEN_OPT_IN, false)
        fun setLockScreenEnabled(context: Context, enabled: Boolean) {
            options(context).edit { putBoolean(LOCK_SCREEN_OPT_IN, enabled) }
        }

        fun canPostLockScreenTimer(context: Context): Boolean {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            return Build.VERSION.SDK_INT < 26 ||
                context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance !=
                NotificationManager.IMPORTANCE_NONE
        }

        fun openFocusIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
