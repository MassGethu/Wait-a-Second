package com.example.waitasecond.service.overlay

import android.content.Context
import android.content.Intent
import com.example.waitasecond.data.repository.FocusLog
import com.example.waitasecond.ui.home.MainActivity

fun interface FocusNavigator { fun openSession(): Boolean }

/** Called only after Go Back. Showing a warning must never invoke Activity navigation. */
class AndroidFocusNavigator(private val context: Context) : FocusNavigator {
    override fun openSession(): Boolean = try {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        // NO_USER_ACTION avoids emitting onUserLeaveHint to a video Activity during our explicit return.
        // It does not control another app's independent PiP policy.
        FocusLog.write("NAVIGATION", "Go Back opened focus Activity")
        true
    } catch (_: RuntimeException) {
        FocusLog.write("NAVIGATION", "Focus Activity launch rejected")
        false
    }
}
