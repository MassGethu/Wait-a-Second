package com.example.waitasecond.ui.planning

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.waitasecond.R
import com.example.waitasecond.core.planning.*
import com.example.waitasecond.core.session.FocusMode
import com.example.waitasecond.data.repository.SessionManager
import com.example.waitasecond.service.scheduling.FocusScheduler
import com.example.waitasecond.ui.home.MainActivity
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Small CRUD screen; domain validation, persistence and alarms remain outside the UI. */
class PlanningActivity : AppCompatActivity() {
    private var rendered: String? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { rendered=null; render() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); setContentView(R.layout.activity_planning)
        com.example.waitasecond.util.FocusWindow.apply(this,findViewById(R.id.planningRoot))
        findViewById<Button>(R.id.planningBack).setOnClickListener { finish() }
        SessionManager.state.observe(this) { if(!it.loading) render() }
    }
    override fun onResume() {
        super.onResume()
        rendered=null
        SessionManager.whenReady { FocusScheduler.reschedule(this); render() }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,12,24,12) }
    private fun LinearLayout.label(value: String) { addView(TextView(this@PlanningActivity).apply { text=value; textSize=16f; setPadding(0,16,0,8) }) }
    private fun LinearLayout.button(value: String, enabled: Boolean = true, action: () -> Unit) {
        addView(Button(this@PlanningActivity).apply { text=value; isEnabled=enabled; setOnClickListener { action() } })
    }
    private fun openSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this,R.string.permission_settings_missing,Toast.LENGTH_LONG).show() }
    }
    private fun render() {
        if(isFinishing || isDestroyed) return
        val signature = "${SessionManager.presets()}${SessionManager.schedules()}${SessionManager.isSessionActive()}${SessionManager.state.value?.scheduleRuns}"
        if(signature==rendered) return
        rendered=signature
        val list = findViewById<LinearLayout>(R.id.planningList); list.removeAllViews()
        if(!FocusScheduler.exactAllowed(this)) {
            list.label(getString(R.string.approximate_schedule))
            if(Build.VERSION.SDK_INT >= 31) list.button(getString(R.string.allow_exact)) {
                openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,android.net.Uri.parse("package:$packageName")))
            }
        }
        if(!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            list.label(getString(R.string.battery_schedule_note))
            list.button(getString(R.string.battery_settings)) { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        if(Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            list.label(getString(R.string.notification_schedule_note))
            list.button(getString(R.string.enable_notifications)) { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        list.label(getString(R.string.focus_presets))
        list.button(getString(R.string.add_preset)) { editPreset(null) }
        SessionManager.presets().forEach { preset ->
            val card=column(); list.addView(card)
            card.label("${preset.name} · ${preset.durationMinutes} min · ${preset.mode.name.lowercase()}\n${preset.goal}")
            card.button(getString(R.string.start_preset),!SessionManager.isSessionActive()) {
                SessionManager.startSession(preset.goal,preset.durationMinutes,preset.mode)
                startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish()
            }
            card.button(getString(R.string.edit_preset)) { editPreset(preset) }
            card.button(getString(R.string.delete_preset)) {
                AlertDialog.Builder(this).setMessage(R.string.delete_preset_confirm).setPositiveButton(R.string.delete_item) { _,_ -> SessionManager.deletePreset(preset.id) }.setNegativeButton(R.string.cancel,null).show()
            }
        }
        list.label(getString(R.string.recurring_schedules))
        list.button(getString(R.string.add_schedule),SessionManager.presets().isNotEmpty()) { editSchedule(null) }
        SessionManager.schedules().forEach { schedule ->
            val preset = SessionManager.presets().firstOrNull { it.id == schedule.presetId }
            val days = schedule.days.sorted().joinToString(" ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT,Locale.getDefault()) }
            list.label("${preset?.name.orEmpty()} · %02d:%02d · %s".format(schedule.hour,schedule.minute,days))
            list.addView(Switch(this).apply {
                text=getString(R.string.schedule_enabled); isChecked=schedule.enabled
                setOnCheckedChangeListener { _,enabled -> SessionManager.saveSchedule(schedule.copy(enabled=enabled)) }
            })
            list.button(getString(R.string.edit_schedule)) { editSchedule(schedule) }
            list.button(getString(R.string.delete_schedule)) { SessionManager.deleteSchedule(schedule.id) }
        }
        val runs = SessionManager.state.value?.scheduleRuns.orEmpty().takeLast(5).asReversed()
        if(runs.isNotEmpty()) list.label(getString(R.string.schedule_history)+"\n"+runs.joinToString("\n") {
            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.fireAt))+" · "+getString(when(it.result) {
                "STARTED" -> R.string.schedule_run_started
                "SKIPPED_ACTIVE" -> R.string.schedule_run_active
                else -> R.string.schedule_run_expired
            })
        })
    }
    private fun field(parent: LinearLayout, label: Int, value: String, numeric: Boolean = false): EditText {
        parent.label(getString(label))
        return EditText(this).apply {
            setText(value); hint=getString(label); isSingleLine=true
            inputType=if(numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            filters=arrayOf(android.text.InputFilter.LengthFilter(if(numeric) 3 else 80))
            parent.addView(this)
        }
    }
    private fun editPreset(old: FocusPreset?) {
        val form=column()
        val name=field(form,R.string.preset_name,old?.name.orEmpty())
        val goal=field(form,R.string.preset_goal,old?.goal.orEmpty())
        val minutes=field(form,R.string.preset_minutes,(old?.durationMinutes ?: 25).toString(),true)
        val mode=Spinner(this).apply {
            adapter=ArrayAdapter(this@PlanningActivity,android.R.layout.simple_spinner_dropdown_item,listOf(getString(R.string.mode_light),getString(R.string.mode_strict)))
            setSelection(if(old?.mode == FocusMode.STRICT) 1 else 0)
        }; form.addView(mode)
        val dialog=AlertDialog.Builder(this).setTitle(if(old==null) R.string.add_preset else R.string.edit_preset)
            .setView(ScrollView(this).apply { addView(form) }).setPositiveButton(R.string.save_item,null).setNegativeButton(R.string.cancel,null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                SessionManager.savePreset(FocusPreset(old?.id ?: java.util.UUID.randomUUID().toString(),name.text.toString().trim(),goal.text.toString().trim(),minutes.text.toString().toInt(),if(mode.selectedItemPosition==1) FocusMode.STRICT else FocusMode.LIGHT))
                dialog.dismiss()
            } catch (_: IllegalArgumentException) { Toast.makeText(this,R.string.preset_validation,Toast.LENGTH_LONG).show() }
        } }; dialog.show()
    }
    private fun editSchedule(old: FocusSchedule?) {
        val presets=SessionManager.presets(); if(presets.isEmpty()) return
        val form=column()
        val preset=Spinner(this).apply {
            adapter=ArrayAdapter(this@PlanningActivity,android.R.layout.simple_spinner_dropdown_item,presets.map { it.name })
            setSelection(presets.indexOfFirst { it.id==old?.presetId }.coerceAtLeast(0))
        }; form.addView(preset)
        val days=(1..7).associateWith { day -> CheckBox(this).apply {
            text=DayOfWeek.of(day).getDisplayName(TextStyle.FULL,Locale.getDefault())
            isChecked=old?.days?.contains(day) ?: (day<=5); form.addView(this)
        } }
        val time=TimePicker(this).apply { setIs24HourView(true); hour=old?.hour ?: 9; minute=old?.minute ?: 0 }; form.addView(time)
        val enabled=CheckBox(this).apply { text=getString(R.string.schedule_enabled); isChecked=old?.enabled ?: true }; form.addView(enabled)
        val dialog=AlertDialog.Builder(this).setTitle(if(old==null) R.string.add_schedule else R.string.edit_schedule)
            .setView(ScrollView(this).apply { addView(form) }).setPositiveButton(R.string.save_item,null).setNegativeButton(R.string.cancel,null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                SessionManager.saveSchedule(FocusSchedule(old?.id ?: java.util.UUID.randomUUID().toString(),presets[preset.selectedItemPosition].id,days.filterValues { it.isChecked }.keys,time.hour,time.minute,enabled.isChecked,lastHandledAt=old?.lastHandledAt))
                dialog.dismiss()
            } catch (_: IllegalArgumentException) { Toast.makeText(this,R.string.schedule_validation,Toast.LENGTH_LONG).show() }
        } }; dialog.show()
    }
}
