package com.example.waitasecond.service.scheduling

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.example.waitasecond.R
import com.example.waitasecond.core.planning.*
import com.example.waitasecond.data.repository.SessionManager
import com.example.waitasecond.service.presentation.FocusSessionSurfaces
import com.example.waitasecond.ui.home.MainActivity
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Entry points serialize through the application session owner, including duplicate deliveries. */
object FocusScheduler {
    const val FIRE = "com.example.waitasecond.SCHEDULE_FIRE"
    const val END = "com.example.waitasecond.SCHEDULE_END"
    private const val CHANNEL = "scheduled_focus"
    fun exactAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private fun alarmIntent(context: Context, id: String, fire: Long = 0) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ScheduleReceiver::class.java).setAction(FIRE).setData(android.net.Uri.parse("waitasecond://schedule/$id"))
            .putExtra("scheduleId",id).putExtra("fireAt",fire), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context,id))
        WorkManager.getInstance(context).cancelUniqueWork("focus-$id")
    }
    fun reschedule(context: Context, clockChanged: Boolean = false) {
        SessionManager.whenReady {
            val now = System.currentTimeMillis()
            SessionManager.schedules().forEach { original ->
                if (!original.enabled) { cancel(context,original.id); return@forEach }
                // Persisted deadlines catch up after reboot/process death, but local-time changes
                // require a fresh calendar calculation rather than using an obsolete zone.
                if (!clockChanged && original.nextFireAt?.let { it <= now } == true) {
                    deliver(context, original.id, original.nextFireAt!!)
                    return@forEach
                }
                val fire = if (clockChanged) SchedulePolicy.nextFire(original,now,ZoneId.systemDefault())
                    else original.nextFireAt ?: SchedulePolicy.nextFire(original,now,ZoneId.systemDefault())
                val schedule = original.copy(nextFireAt = fire)
                SessionManager.updateScheduleTime(schedule)
                arm(context,schedule,fire)
            }
        }
    }
    private fun arm(context: Context, schedule: FocusSchedule, fire: Long) {
        cancel(context,schedule.id)
        if (exactAllowed(context)) {
            try {
                context.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,fire,alarmIntent(context,schedule.id,fire))
                return
            } catch (_: SecurityException) { /* Permission changed between checking and scheduling. */ }
        }
        val request = OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInitialDelay((fire-System.currentTimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("scheduleId" to schedule.id,"fireAt" to fire)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("focus-${schedule.id}",ExistingWorkPolicy.REPLACE,request)
    }
    fun deliver(context: Context, id: String, fire: Long) {
        val schedule = SessionManager.schedules().firstOrNull { it.id == id && it.enabled } ?: return
        if (schedule.nextFireAt != fire || schedule.lastHandledAt?.let { it >= fire } == true) return
        val now = System.currentTimeMillis()
        if (now < fire) { arm(context,schedule,fire); return }
        val preset = SessionManager.presets().firstOrNull { it.id == schedule.presetId } ?: return
        val active = SessionManager.isSessionActive() || SessionManager.occupiedAt(fire)
        val remaining = SchedulePolicy.remaining(preset.durationMinutes,fire,now,active)
        // Mark the occurrence before publishing a new session or processing another delivery.
        val next = schedule.copy(lastHandledAt = fire, nextFireAt = SchedulePolicy.nextFire(schedule,now,ZoneId.systemDefault()))
        SessionManager.updateScheduleTime(next)
        if (remaining > 0) {
            val session = SessionManager.startScheduledSession(preset.goal,preset.durationMinutes,preset.mode,remaining)
            SessionManager.recordRun(ScheduleRun("$id:$fire",id,fire,"STARTED",session.id))
            notifyStarted(context,preset,session.id)
        } else SessionManager.recordRun(ScheduleRun("$id:$fire",id,fire,if(active) "SKIPPED_ACTIVE" else "SKIPPED_EXPIRED"))
        arm(context,next,next.nextFireAt!!)
    }
    private fun notifyStarted(context: Context, preset: FocusPreset, sessionId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL,context.getString(R.string.schedule_channel),NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val end = PendingIntent.getBroadcast(context,0,Intent(context,ScheduleReceiver::class.java).setAction(END)
            .setData(android.net.Uri.parse("waitasecond://end/$sessionId")).putExtra("sessionId",sessionId),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_focus)
            .setContentTitle(context.getString(R.string.scheduled_started)).setContentText(preset.name)
            .setContentIntent(open).setAutoCancel(true).addAction(0,context.getString(R.string.skip_end),end).build()
        try { NotificationManagerCompat.from(context).notify(sessionId.hashCode(),notification) } catch (_: SecurityException) { /* Denial does not stop the timer. */ }
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        SessionManager.whenReady {
            try {
                when(intent.action) {
                    FocusScheduler.FIRE -> intent.getStringExtra("scheduleId")?.let { FocusScheduler.deliver(context,it,intent.getLongExtra("fireAt",0)) }
                    FocusSessionSurfaces.ACTION_EXPIRE -> SessionManager.refreshFocusSurfaces()
                    FocusScheduler.END -> {
                        val id = intent.getStringExtra("sessionId")
                        if (id != null && SessionManager.activeSession()?.id == id) SessionManager.endSession()
                        if(id != null) context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
                    }
                    else -> {
                        FocusScheduler.reschedule(context,intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED)
                        SessionManager.refreshFocusSurfaces()
                    }
                }
            } finally { SessionManager.flush { pending.finish() } }
        }
    }
}
class ScheduleWorker(context: Context, params: WorkerParameters) : Worker(context,params) {
    override fun doWork(): Result {
        val done = CountDownLatch(1)
        SessionManager.whenReady {
            try { inputData.getString("scheduleId")?.let { FocusScheduler.deliver(applicationContext,it,inputData.getLong("fireAt",0)) } }
            finally { SessionManager.flush { done.countDown() } }
        }
        return if(done.await(30,TimeUnit.SECONDS)) Result.success() else Result.retry()
    }
}
