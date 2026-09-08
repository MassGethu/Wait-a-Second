package com.example.waitasecond

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class InterventionActivity : AppCompatActivity() {
    private var contentKey: String = "unknown"
    private var targetPackage: String = ""
    private var actionChosen = false
    private var continueReady = false
    private var continueCountdown: CountDownTimer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val expiryCheck = object : Runnable {
        override fun run() {
            if (!SessionManager.isSessionActive()) finish() else {
                findViewById<TextView>(R.id.interventionRemaining).text = FocusFormat.timer(SessionManager.remainingMillis())
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intervention)
        FocusWindow.apply(this, findViewById(R.id.overlayRoot))

        contentKey = intent.getStringExtra(EXTRA_CONTENT_KEY) ?: "unknown"
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()
        val topic = FocusFormat.topic(SessionManager.activeSession()?.goal
            ?: intent.getStringExtra(EXTRA_GOAL) ?: getString(R.string.default_focus_goal))
        findViewById<TextView>(R.id.interventionGoal).text = topic
        findViewById<TextView>(R.id.interventionMessage).text = getString(R.string.overlay_message, topic)
        findViewById<TextView>(R.id.interventionSource).text =
            if (targetPackage == SessionTracker.INSTAGRAM_PACKAGE) "Instagram Reels" else "YouTube"
        findViewById<TextView>(R.id.interventionContent).text =
            intent.getStringExtra(EXTRA_CONTENT) ?: getString(R.string.unknown_content)

        findViewById<Button>(R.id.goBackButton).setOnClickListener {
            if (actionChosen) return@setOnClickListener
            openDistractingAppHome()
        }

        findViewById<Button>(R.id.continueButton).setOnClickListener {
            if (continueReady) continueAnyway() else startContinueCountdown()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Treat system Back like Continue Anyway so it cannot create a reopen loop.
                continueAnyway()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!SessionManager.isSessionActive() ||
            (!SessionManager.interventionLaunched(contentKey) && !SessionManager.isCurrentIntervention(contentKey))) { finish(); return }
        SessionManager.foregroundChanged(SessionTracker.OWN_PACKAGE, interventionWindow = true)
        handler.post(expiryCheck)
    }

    override fun onPause() {
        handler.removeCallbacks(expiryCheck)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        continueCountdown?.cancel()
        // Home/app-switch must not leave a hidden intervention locking the detector.
        if (!isChangingConfigurations && !actionChosen) {
            SessionManager.closeIntervention(contentKey)
            finish()
        }
    }

    override fun onDestroy() {
        continueCountdown?.cancel()
        handler.removeCallbacksAndMessages(null)
        if (!isChangingConfigurations) SessionManager.closeIntervention(contentKey)
        super.onDestroy()
    }

    private fun startContinueCountdown() {
        if (actionChosen || continueCountdown != null) return
        val button = findViewById<Button>(R.id.continueButton)
        button.isEnabled = false
        button.text = getString(R.string.continue_countdown, 10)
        continueCountdown = object : CountDownTimer(10_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                button.text = getString(R.string.continue_countdown, (millisUntilFinished + 999) / 1_000)
            }

            override fun onFinish() {
                continueReady = true
                button.text = getString(R.string.continue_ready)
                button.isEnabled = true
            }
        }.start()
    }

    private fun continueAnyway() {
        if (actionChosen) return
        continueCountdown?.cancel()
        actionChosen = true
        SessionManager.closeIntervention(contentKey)
        Log.i(LOG_TAG, "Continue Anyway selected; current episode remains suppressed")
        finish()
    }

    private fun openDistractingAppHome() {
        if (!SessionManager.isCurrentIntervention(contentKey)) {
            finish()
            return
        }

        if (targetPackage !in setOf(SessionTracker.YOUTUBE_PACKAGE, SessionTracker.INSTAGRAM_PACKAGE)) {
            Toast.makeText(this, R.string.app_home_failed, Toast.LENGTH_LONG).show()
            return
        }

        val launcherComponent = packageManager
            .getLaunchIntentForPackage(targetPackage)
            ?.component
        if (launcherComponent == null) {
            Log.e(LOG_TAG, "No launcher Activity found for $targetPackage")
            Toast.makeText(this, R.string.app_home_failed, Toast.LENGTH_LONG).show()
            return
        }

        val appHomeIntent = Intent.makeRestartActivityTask(
            ComponentName(launcherComponent.packageName, launcherComponent.className)
        )

        try {
            startActivity(appHomeIntent)
            SessionManager.leaveInterventionForAppHome(contentKey)
            actionChosen = true
            Log.i(LOG_TAG, "Go Back selected; opened $targetPackage home and kept session active")
            finish()
        } catch (error: RuntimeException) {
            Log.e(LOG_TAG, "Unable to open $targetPackage home", error)
            Toast.makeText(this, R.string.app_home_failed, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_GOAL = "goal"
        const val EXTRA_CONTENT = "content"
        const val EXTRA_REASON = "reason"
        const val EXTRA_CONTENT_KEY = "content_key"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        private const val LOG_TAG = "WaitaSecond"
    }
}
