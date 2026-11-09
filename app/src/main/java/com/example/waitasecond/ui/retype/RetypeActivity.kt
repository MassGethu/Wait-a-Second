package com.example.waitasecond.ui.retype

import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.waitasecond.R
import com.example.waitasecond.core.distraction.InterventionPhase
import com.example.waitasecond.core.distraction.RetypePolicy
import com.example.waitasecond.data.repository.SessionManager

/** Separate task so finishing returns to the app beneath it; drafts never enter saved instance state. */
class RetypeActivity : AppCompatActivity() {
    private var token: String? = null
    private var completed = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); setContentView(R.layout.activity_retype)
        com.example.waitasecond.util.FocusWindow.apply(this, findViewById(R.id.retypeRoot))
        token = intent.getStringExtra(EXTRA_TOKEN)
        findViewById<Button>(R.id.retypeContinue).isEnabled = false
        SessionManager.whenReady {
            if (isFinishing || isDestroyed) return@whenReady
            val key = token ?: return@whenReady finish()
            val sentence = SessionManager.retypeSentence(key) ?: return@whenReady finish()
            if (SessionManager.interventionState().phase != InterventionPhase.LIGHT_RETYPE) return@whenReady finish()
            findViewById<TextView>(R.id.retypeSentence).text = sentence
            val input = findViewById<EditText>(R.id.retypeInput).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }
                if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
                isSaveEnabled = false
                customInsertionActionModeCallback = NoPasteCallback
                customSelectionActionModeCallback = NoPasteCallback
                setOnLongClickListener { true }
            }
            val button = findViewById<Button>(R.id.retypeContinue)
            input.doAfterTextChanged { value ->
                val typed = RetypePolicy.normalize(value?.toString().orEmpty())
                val expected = RetypePolicy.normalize(sentence)
                val feedback = SpannableString(typed)
                typed.indices.forEach { i -> feedback.setSpan(ForegroundColorSpan(
                    if (i < expected.length && typed[i] == expected[i]) 0xff56c991.toInt() else 0xffef7777.toInt()), i, i+1, 0) }
                findViewById<TextView>(R.id.retypeFeedback).text = feedback
                button.isEnabled = RetypePolicy.matches(sentence, value?.toString().orEmpty())
            }
            button.setOnClickListener {
                if (SessionManager.completeRetype(key, input.text.toString())) { completed = true; input.text.clear(); finish() }
            }
        }
        SessionManager.state.observe(this) {
            if (!it.loading && (it.session?.isActive != true || it.intervention.token != token || it.intervention.phase != InterventionPhase.LIGHT_RETYPE)) finish()
        }
    }
    override fun onStop() {
        if (!completed && !isChangingConfigurations) { token?.let(SessionManager::cancelRetype); finish() }
        super.onStop()
    }
    private object NoPasteCallback : android.view.ActionMode.Callback {
        override fun onCreateActionMode(m: android.view.ActionMode?, menu: android.view.Menu?) = false
        override fun onPrepareActionMode(m: android.view.ActionMode?, menu: android.view.Menu?) = false
        override fun onActionItemClicked(m: android.view.ActionMode?, item: android.view.MenuItem?) = false
        override fun onDestroyActionMode(m: android.view.ActionMode?) {}
    }
    companion object { const val EXTRA_TOKEN = "token" }
}

/** Reject platform paste and drops even when an input method exposes alternate paste controls. */
class RetypeInput @JvmOverloads constructor(context: android.content.Context, attrs: android.util.AttributeSet? = null) :
    androidx.appcompat.widget.AppCompatEditText(context, attrs) {
    override fun onTextContextMenuItem(id: Int): Boolean = when(id) {
        android.R.id.paste, android.R.id.pasteAsPlainText -> true
        else -> super.onTextContextMenuItem(id)
    }
    override fun onDragEvent(event: android.view.DragEvent) = true
    override fun autofill(value: android.view.autofill.AutofillValue?) { /* No autofill. */ }
}
