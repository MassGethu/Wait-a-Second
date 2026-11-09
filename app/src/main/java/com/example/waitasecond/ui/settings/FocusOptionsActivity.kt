package com.example.waitasecond.ui.settings

import android.content.ActivityNotFoundException
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import com.example.waitasecond.R
import com.example.waitasecond.data.local.TrustedContactStore
import com.example.waitasecond.util.FocusWindow

class FocusOptionsActivity : AppCompatActivity() {
    private val options by lazy { getSharedPreferences("focus_options", MODE_PRIVATE) }
    private val trustedContacts by lazy { TrustedContactStore(options) }
    private val contactPicker = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null && options.getBoolean(TRUSTED_CONTACTS_GUIDE, false)) {
            if (trustedContacts.addFromPicker(contentResolver, uri)) renderContacts()
            else Toast.makeText(this, R.string.trusted_contact_pick_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_focus_options)
        FocusWindow.apply(this, findViewById(R.id.focusOptionsRoot))
        findViewById<Button>(R.id.focusOptionsBack).setOnClickListener { finish() }

        val guide = findViewById<View>(R.id.grayscaleGuide)
        findViewById<SwitchCompat>(R.id.grayscaleOptIn).apply {
            isChecked = options.getBoolean(GRAYSCALE_GUIDE, false)
            guide.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, enabled ->
                options.edit { putBoolean(GRAYSCALE_GUIDE, enabled) }
                guide.visibility = if (enabled) View.VISIBLE else View.GONE
            }
        }
        findViewById<Button>(R.id.grayscaleAndroidSettings).setOnClickListener {
            openSystemScreen(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val trustedGuide = findViewById<View>(R.id.trustedContactsGuide)
        findViewById<SwitchCompat>(R.id.trustedContactsOptIn).apply {
            isChecked = options.getBoolean(TRUSTED_CONTACTS_GUIDE, false)
            trustedGuide.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, enabled ->
                options.edit { putBoolean(TRUSTED_CONTACTS_GUIDE, enabled) }
                trustedGuide.visibility = if (enabled) View.VISIBLE else View.GONE
                if (enabled) renderDndStatus()
            }
        }
        findViewById<Button>(R.id.addTrustedContact).setOnClickListener {
            try { contactPicker.launch(null) }
            catch (_: ActivityNotFoundException) { showSystemScreenUnavailable() }
            catch (_: SecurityException) { showSystemScreenUnavailable() }
        }
        findViewById<Button>(R.id.openDndSettings).setOnClickListener {
            val soundSettings = Intent(Settings.ACTION_SOUND_SETTINGS)
            if (Build.VERSION.SDK_INT >= 26) {
                openSystemScreen(Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS), soundSettings)
            } else openSystemScreen(soundSettings)
        }
        renderContacts()
    }

    override fun onResume() {
        super.onResume()
        if (options.getBoolean(TRUSTED_CONTACTS_GUIDE, false)) renderDndStatus()
    }

    private fun renderDndStatus() {
        val status = when (getSystemService(NotificationManager::class.java).currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> R.string.trusted_dnd_off
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> R.string.trusted_dnd_priority
            NotificationManager.INTERRUPTION_FILTER_NONE, NotificationManager.INTERRUPTION_FILTER_ALARMS -> R.string.trusted_dnd_other
            else -> R.string.trusted_dnd_unavailable
        }
        findViewById<TextView>(R.id.trustedDndStatus).setText(status)
    }

    private fun renderContacts() {
        val list = findViewById<LinearLayout>(R.id.trustedContactList)
        list.removeAllViews()
        val contacts = trustedContacts.contacts()
        findViewById<View>(R.id.trustedContactsEmpty).visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
        contacts.forEach { contact ->
            val row = layoutInflater.inflate(R.layout.row_trusted_contact, list, false)
            val label = contact.label.ifBlank { getString(R.string.trusted_contact_unnamed) }
            row.findViewById<TextView>(R.id.trustedContactName).text = label
            row.findViewById<Button>(R.id.openTrustedContact).setOnClickListener {
                openSystemScreen(Intent(Intent.ACTION_VIEW, contact.uri))
            }
            row.findViewById<Button>(R.id.removeTrustedContact).setOnClickListener {
                AlertDialog.Builder(this).setTitle(getString(R.string.trusted_contact_remove_title, label))
                    .setMessage(R.string.trusted_contact_remove_hint)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.trusted_contact_remove) { _, _ ->
                        trustedContacts.remove(contact)
                        renderContacts()
                    }.show()
            }
            list.addView(row)
        }
    }

    private fun openSystemScreen(intent: Intent, fallback: Intent? = null) {
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) {
            if (fallback != null) openSystemScreen(fallback) else showSystemScreenUnavailable()
        } catch (_: SecurityException) {
            if (fallback != null) openSystemScreen(fallback) else showSystemScreenUnavailable()
        }
    }

    private fun showSystemScreenUnavailable() {
        Toast.makeText(this, R.string.focus_system_screen_unavailable, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val GRAYSCALE_GUIDE = "grayscale_guide"
        const val TRUSTED_CONTACTS_GUIDE = "trusted_contacts_guide"
    }
}
